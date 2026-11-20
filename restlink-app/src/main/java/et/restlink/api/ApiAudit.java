package et.restlink.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * API-level audit helpers. Never persist raw keys, Authorization headers, or PAN-shaped values.
 */
public final class ApiAudit {

    public static final String ACTOR_PROPERTY = "restlink.audit.actor";
    public static final String HEADER_ADMIN = "X-Restlink-Admin-Key";

    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            HEADER_ADMIN.toLowerCase(Locale.ROOT),
            ApiAuth.HEADER.toLowerCase(Locale.ROOT),
            "idempotency-key");

    /** 13–19 digits (optionally grouped) — typical PAN / track data. */
    private static final Pattern PAN = Pattern.compile("\\b[0-9][0-9 -]{11,21}[0-9]\\b");

    private ApiAudit() {}

    public static boolean mutating(String method) {
        if (method == null) {
            return false;
        }
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    /** Stable actor label: {@code admin} or {@code api:<12-hex>} — never the raw secret. */
    public static String actorId(boolean admin, String providedApiKey) {
        if (admin) {
            return "admin";
        }
        if (providedApiKey == null || providedApiKey.isBlank()) {
            return "anonymous";
        }
        return "api:" + sha256Hex(providedApiKey).substring(0, 12);
    }

    public static String tenantFromPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        String[] parts = p.split("/");
        if (parts.length >= 2 && ("customers".equals(parts[0]) || "wallet".equals(parts[0]))) {
            if ("cash-in".equals(parts[1]) || "cash-out".equals(parts[1])
                    || "transfer".equals(parts[1]) || "open".equals(parts[1])
                    || "response".equals(parts[1])) {
                return "";
            }
            return parts[1];
        }
        return "";
    }

    public static String sanitizePath(String path) {
        return redactPan(path == null ? "" : path);
    }

    public static String clientIp(String forwardedFor, String remoteAddr) {
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            String first = comma < 0 ? forwardedFor : forwardedFor.substring(0, comma);
            return first.trim();
        }
        return remoteAddr == null ? "" : remoteAddr;
    }

    public static boolean isSecretHeader(String name) {
        return name != null && SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static String redactPan(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        return PAN.matcher(raw).replaceAll("[PAN]");
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }
}
