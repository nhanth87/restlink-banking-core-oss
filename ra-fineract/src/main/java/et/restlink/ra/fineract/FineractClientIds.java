package et.restlink.ra.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Resolves Fineract's numeric client PK from Restlink's string {@code customerExternalId}.
 * Fineract {@code /savingsaccounts} {@code clientId} is a number — never the KYC/external string.
 */
public final class FineractClientIds {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FineractClientIds() {}

    public static String lookupPath(String customerExternalId) {
        String ext = require(customerExternalId);
        return "/clients?externalId=" + URLEncoder.encode(ext, StandardCharsets.UTF_8);
    }

    /**
     * Parse {@code GET /clients?externalId=} (pageItems) or a single-client body.
     */
    public static Optional<String> parseClientId(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = JSON.readTree(body);
            JsonNode items = n.get("pageItems");
            if (items != null && items.isArray() && !items.isEmpty()) {
                return firstId(items.get(0));
            }
            return firstId(n);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Optional<Long> numericClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        String t = clientId.trim();
        if (!t.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(t));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> firstId(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return Optional.empty();
        }
        for (String f : new String[] {"id", "clientId", "resourceId"}) {
            if (n.hasNonNull(f)) {
                String v = n.get(f).asText();
                if (v != null && !v.isBlank()) {
                    return Optional.of(v);
                }
            }
        }
        return Optional.empty();
    }

    private static String require(String v) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("customerExternalId required");
        }
        return v.trim();
    }
}
