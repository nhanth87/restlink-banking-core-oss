package et.restlink.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterModePolicyTest {

    @Test
    void distSyncHasNoWarning() {
        assertTrue(ClusterModePolicy.isDistSync("dist_sync"));
        assertNull(ClusterModePolicy.localWarning("DIST_SYNC"));
    }

    @Test
    void localWarnsSingleNodeOnly() {
        assertFalse(ClusterModePolicy.isDistSync("LOCAL"));
        String w = ClusterModePolicy.localWarning("LOCAL");
        assertTrue(w.contains("DIST_SYNC"));
        assertEquals("LOCAL", ClusterModePolicy.normalize(null));
    }
}
