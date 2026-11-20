package et.restlink.cluster;

/**
 * P1 cluster mode policy. {@code LOCAL} is the single-node lab default; N≥2 must set DIST_SYNC.
 */
public final class ClusterModePolicy {

    public static final String LOCAL = "LOCAL";
    public static final String DIST_SYNC = "DIST_SYNC";

    private ClusterModePolicy() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL;
        }
        return raw.trim().toUpperCase();
    }

    public static boolean isDistSync(String mode) {
        return DIST_SYNC.equals(normalize(mode));
    }

    /**
     * @return a warning for operators when running LOCAL, else {@code null}
     */
    public static String localWarning(String mode) {
        if (isDistSync(mode)) {
            return null;
        }
        return "cluster mode LOCAL — single-node only; set RESTLINK_CLUSTER_MODE=DIST_SYNC for N>=2";
    }
}
