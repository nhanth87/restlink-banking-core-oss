package et.restlink.cluster;

import et.restlink.core.CoreBankingRouter;
import et.restlink.core.PendingRequest;
import et.restlink.core.PendingRequestStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Mid-transaction failover for <em>non-money</em> pending requests only.
 * Money workflows are owned by Temporal — worker crash → Temporal replay; this bridge must
 * never re-drive a payment (would create a second coordinator).
 */
public final class RestlinkClusterViewBridge {

    private static final Logger LOG = LoggerFactory.getLogger(RestlinkClusterViewBridge.class);

    private final ClusterMaps cluster;
    private final PendingRequestStore pending;
    private final CoreBankingRouter router;

    public RestlinkClusterViewBridge(ClusterMaps cluster, PendingRequestStore pending, CoreBankingRouter router) {
        this.cluster = cluster;
        this.pending = pending;
        this.router = router;
    }

    public void start() {
        cluster.onMembersDeparted(this::adoptOrphans);
        LOG.info("[restlink-cluster] view bridge armed on node={} (non-money only; money=Temporal)",
                cluster.localNode());
    }

    void adoptOrphans(List<String> departed) {
        List<PendingRequest> orphans = pending.orphansOf(departed);
        if (orphans.isEmpty()) return;
        LOG.warn("[restlink-cluster] adopting {} orphaned non-money request(s) from {}",
                orphans.size(), departed);
        for (PendingRequest orphan : orphans) {
            if (isMoneyCommand(orphan.commandType())) {
                LOG.info("[restlink-cluster] skip money orphan req={} type={} — Temporal owns replay",
                        orphan.requestId(), orphan.commandType());
                continue;
            }
            Optional<PendingRequest> mine = pending.adopt(orphan);
            if (mine.isEmpty()) {
                LOG.info("[restlink-cluster] skip orphan req={} — another node adopted",
                        orphan.requestId());
                continue;
            }
            resolveNonMoney(mine.get());
        }
    }

    private void resolveNonMoney(PendingRequest req) {
        // OpenAccount / CreateCustomer / QueryBalance: mark for review; never invent money effects.
        pending.mark(req.requestId(), PendingRequest.State.NEEDS_REVIEW);
        LOG.info("[restlink-cluster] req={} adopted by {} -> NEEDS_REVIEW (non-money)",
                req.requestId(), cluster.localNode());
    }

    private static boolean isMoneyCommand(String commandType) {
        if (commandType == null) return false;
        return commandType.contains("PostTransaction")
                || commandType.contains("TransferFunds")
                || commandType.contains("ReverseTransaction")
                || commandType.contains("Payment");
    }
}
