package io.envoyproxy.controlplane.cache;

import com.google.protobuf.Message;
import envoy.api.v2.Discovery.DiscoveryRequest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SimpleCache} provides a default implementation of {@link SnapshotCache}. It maintains a single versioned
 * {@link Snapshot} per node group. For the protocol to work correctly in ADS mode, EDS/RDS requests are responded to
 * only when all resources in the snapshot xDS response are named as part of the request. It is expected that the CDS
 * response names all EDS clusters, and the LDS response names all RDS routes in a snapshot, to ensure that Envoy makes
 * the request for all EDS clusters or RDS routes eventually.
 *
 * <p>The snapshot can be partial, e.g. only include RDS or EDS resources.
 */
public class SimpleCache<T> implements SnapshotCache<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleCache.class);

  private final boolean ads;
  private final NodeGroup<T> groups;

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private final Lock readLock = lock.readLock();
  private final Lock writeLock = lock.writeLock();

  @GuardedBy("lock")
  private final Map<T, Snapshot> snapshots = new HashMap<>();
  @GuardedBy("lock")
  private final Map<T, CacheStatusInfo> statuses = new HashMap<>();

  @GuardedBy("lock")
  private long watchCount;

  /**
   * Constructs a simple cache.
   *
   * @param ads if running in ADS-mode, responses are delayed until all resources are explicitly named
   * @param groups maps an envoy host to a node group
   */
  public SimpleCache(boolean ads, NodeGroup<T> groups) {
    this.ads = ads;
    this.groups = groups;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Watch createWatch(DiscoveryRequest request) {
    T group = groups.hash(request.getNode());

    writeLock.lock();

    try {
      CacheStatusInfo status = statuses.computeIfAbsent(group, g -> new CacheStatusInfo(request.getNode()));

      status.setLastWatchRequestTime(System.currentTimeMillis());

      Snapshot snapshot = snapshots.get(group);
      String version = snapshot == null ? "" : snapshot.version(request.getTypeUrl());

      Watch watch = new Watch(request);

      // If the requested version is up-to-date or missing a response, leave an open watch.
      if (snapshot == null || request.getVersionInfo().equals(version)) {
        watchCount++;

        long watchId = watchCount;

        LOGGER.info("open watch {} for {}[{}] from node {} for version {}",
            watchId,
            request.getTypeUrl(),
            String.join(", ", request.getResourceNamesList()),
            group,
            request.getVersionInfo());

        status.setWatch(watchId, watch);

        watch.setStop(() -> {
          writeLock.lock();

          try {
            status.removeWatch(watchId);
          } finally {
            writeLock.unlock();
          }
        });

        return watch;
      }

      // Otherwise, the watch may be responded immediately
      respond(watch, snapshot, group);

      return watch;

    } finally {
      writeLock.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSnapshot(T group, Snapshot snapshot) {
    writeLock.lock();

    try {
      // Update the existing snapshot entry.
      snapshots.put(group, snapshot);

      CacheStatusInfo status = statuses.get(group);

      if (status == null) {
        return;
      }

      status.watchesRemoveIf((id, watch) -> {
        String version = snapshot.version(watch.request().getTypeUrl());

        if (!watch.request().getVersionInfo().equals(version)) {
          LOGGER.info("responding to open watch {}[{}] with new version {}",
              id,
              String.join(", ", watch.request().getResourceNamesList()),
              version);

          respond(watch, snapshot, group);

          // Discard the watch. A new watch will be created for future snapshots once envoy ACKs the response.
          return true;
        }

        // Do not discard the watch. The request version is the same as the snapshot version, so we wait to respond.
        return false;
      });
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StatusInfo statusInfo(T group) {
    readLock.lock();

    try {
      return statuses.get(group);
    } finally {
      readLock.unlock();
    }
  }

  private Response createResponse(DiscoveryRequest request, Map<String, ? extends Message> resources, String version) {
    Collection<? extends Message> filtered = request.getResourceNamesList().isEmpty()
        ? resources.values()
        : request.getResourceNamesList().stream()
            .map(resources::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return Response.create(request, filtered, version);
  }

  private void respond(Watch watch, Snapshot snapshot, T group) {
    Map<String, ? extends Message> snapshotResources = snapshot.resources(watch.request().getTypeUrl());

    if (!watch.request().getResourceNamesList().isEmpty() && ads) {
      Collection<String> missingNames = watch.request().getResourceNamesList().stream()
          .filter(name -> !snapshotResources.containsKey(name))
          .collect(Collectors.toList());

      if (!missingNames.isEmpty()) {
        LOGGER.info("not responding in ADS mode for {} from node {} at version {} since [{}] not requested in [{}]",
            watch.request().getTypeUrl(),
            group,
            snapshot.version(watch.request().getTypeUrl()),
            String.join(", ", missingNames),
            String.join(", ", watch.request().getResourceNamesList()));

        return;
      }
    }

    String version = snapshot.version(watch.request().getTypeUrl());

    LOGGER.info("responding for {} from node {} at version {} with version {}",
        watch.request().getTypeUrl(),
        group,
        watch.request().getVersionInfo(),
        version);

    Response response = createResponse(
        watch.request(),
        snapshotResources,
        version);

    watch.valueEmitter().onNext(response);
  }
}