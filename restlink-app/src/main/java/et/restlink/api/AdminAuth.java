package et.restlink.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared admin-key gate for operator endpoints. Constant-time compare — never {@code String.equals}.
 */
public final class AdminAuth {

    /** Lab default — treat as unset; reject until {@code RESTLINK_ADMIN_KEY} is configured. */
    public static final String UNSET_DEFAULT = "change-me";
    /** Floor so a rotated secret is not a one-character placeholder. */
    public static final int MIN_LENGTH = 12;

    private AdminAuth() {}

    /**
     * @return {@code null} if authorized; otherwise a short error code for the response body
     */
    public static String denyReason(String configuredKey, String providedHeader) {
        if (!isConfigured(configuredKey)) {
            return "admin key not configured";
        }
        if (providedHeader == null || !constantTimeEquals(configuredKey, providedHeader)) {
            return "bad admin key";
        }
        return null;
    }

    /** True when the configured secret is usable (not blank, not the lab default, long enough). */
    public static boolean isConfigured(String configuredKey) {
        return configuredKey != null
                && !configuredKey.isBlank()
                && !UNSET_DEFAULT.equals(configuredKey)
                && configuredKey.length() >= MIN_LENGTH;
    }

    /** Constant-time equality on UTF-8 bytes (dummy compare when lengths differ). */
    public static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) return false;
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            MessageDigest.isEqual(a, a); // keep timing roughly flat on length mismatch
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }
}
