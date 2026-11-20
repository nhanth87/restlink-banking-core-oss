package et.restlink.api;

/**
 * API-key gate for public banking surfaces ({@code /customers}, {@code /wallet}).
 * Constant-time compare — never {@code String.equals}. Admin key is a superuser fallback.
 */
public final class ApiAuth {

    /** Lab default — treat as unset; reject until {@code RESTLINK_API_KEY} is configured. */
    public static final String UNSET_DEFAULT = "change-me";
    public static final String HEADER = "X-Restlink-Api-Key";

    private ApiAuth() {}

    /**
     * @return {@code null} if authorized; otherwise a short error code for the response body
     */
    public static String denyReason(String configuredApiKey, String providedApiKey,
                                    String configuredAdminKey, String providedAdminKey) {
        if (AdminAuth.denyReason(configuredAdminKey, providedAdminKey) == null) {
            return null;
        }
        if (configuredApiKey == null || configuredApiKey.isBlank() || UNSET_DEFAULT.equals(configuredApiKey)) {
            return "api key not configured";
        }
        if (providedApiKey == null || !AdminAuth.constantTimeEquals(configuredApiKey, providedApiKey)) {
            return "bad api key";
        }
        return null;
    }

    public static boolean covers(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.startsWith("customers") || p.startsWith("wallet");
    }
}
