package et.restlink.cluster;

import et.restlink.core.PendingRequest;

import org.infinispan.Cache;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.transport.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Multi-node N–N HA backend: one embedded JGroups view, DIST_SYNC caches for idempotency and pending
 * requests. Uses the JavaSerialization marshaller to avoid protostream schema wiring for internal
 * records. DIST_SYNC ⇒ synchronous replication so a claim/complete is visible cluster-wide before we
 * ack — banking correctness over raw throughput.
 */
@Listener
public final class RestlinkInfinispanManager implements ClusterMaps, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RestlinkInfinispanManager.class);
    private static final String IDEM_CACHE = "restlink-idem";
    private static final String PENDING_CACHE = "restlink-pending";

    private final EmbeddedCacheManager manager;
    private final Cache<String, String> idem;
    private final Cache<String, PendingRequest> pending;
    private final String node;
    private final List<Consumer<List<String>>> departedListeners = new CopyOnWriteArrayList<>();
    private volatile List<String> lastMembers;

    public RestlinkInfinispanManager(String clusterName, String nodeName) {
        GlobalConfigurationBuilder g = new GlobalConfigurationBuilder();
        g.transport().defaultTransport().clusterName(clusterName == null ? "restlink" : clusterName);
        if (nodeName != null && !nodeName.isBlank()) g.transport().nodeName(nodeName);
        g.serialization().marshaller(new JavaSerializationMarshaller())
                .allowList().addRegexp("et\\.restlink\\..*").addRegexp("java\\..*");

        this.manager = new DefaultCacheManager(g.build());
        ConfigurationBuilder dist = new ConfigurationBuilder();
        dist.clustering().cacheMode(CacheMode.DIST_SYNC);
        manager.defineConfiguration(IDEM_CACHE, dist.build());
        manager.defineConfiguration(PENDING_CACHE, dist.build());

        this.idem = manager.getCache(IDEM_CACHE);
        this.pending = manager.getCache(PENDING_CACHE);
        this.node = manager.getAddress() == null ? "restlink-1" : manager.getAddress().toString();
        this.lastMembers = snapshotMembers();
        manager.addListener(this);
        LOG.info("[restlink-cluster] DIST_SYNC up node={} members={}", node, lastMembers);
    }

    @Override public ConcurrentMap<String, String> idem() { return idem; }
    @Override public ConcurrentMap<String, PendingRequest> pending() { return pending; }
    @Override public String localNode() { return node; }
    @Override public String mode() { return "DIST_SYNC"; }
    @Override public List<String> members() { return snapshotMembers(); }

    @Override public void onMembersDeparted(Consumer<List<String>> listener) {
        departedListeners.add(listener);
    }

    @ViewChanged
    public void onView(ViewChangedEvent e) {
        List<String> now = e.getNewMembers().stream().map(Address::toString).toList();
        List<String> before = lastMembers;
        List<String> departed = new ArrayList<>(before);
        departed.removeAll(now);
        lastMembers = now;
        if (!departed.isEmpty()) {
            LOG.warn("[restlink-cluster] members departed={} surviving={}", departed, now);
            departedListeners.forEach(l -> l.accept(departed));
        }
    }

    private List<String> snapshotMembers() {
        List<Address> m = manager.getMembers();
        if (m == null) return List.of(node);
        return m.stream().map(Address::toString).toList();
    }

    @Override public void close() {
        try { manager.stop(); } catch (RuntimeException ignore) { }
    }
}
