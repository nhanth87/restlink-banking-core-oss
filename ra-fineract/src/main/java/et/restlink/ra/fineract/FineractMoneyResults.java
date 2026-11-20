package et.restlink.ra.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.restlink.ra.fineract.client.FineractRestClient.HttpResult;
import et.restlink.spi.model.AccountBalance;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.spi.model.Money;
import et.restlink.spi.payment.MoneyStepResult;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Maps Fineract HTTP outcomes onto saga {@link MoneyStepResult} without flipping link-truth
 * on business 4xx. Transport/5xx are {@code TRANSIENT} so Temporal retries the same Idempotency-Key.
 */
public final class FineractMoneyResults {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FineractMoneyResults() {}

    public static MoneyStepResult ofWrite(HttpResult r, String txnId) {
        if (r == null) {
            return MoneyStepResult.transientFailure("null HTTP result");
        }
        if (r.is2xx()) {
            if (txnId != null && !txnId.isBlank()) {
                return MoneyStepResult.committed(txnId, txnId);
            }
            return MoneyStepResult.ambiguous("2xx without txn id");
        }
        if (r.isBusinessError()) {
            return MoneyStepResult.businessReject("HTTP " + r.status() + ": " + snippet(r.body()));
        }
        String detail = r.transportError() ? r.transportDetail() : "HTTP " + r.status();
        return MoneyStepResult.transientFailure("peer-down: " + detail);
    }

    public static MoneyStepResult ofUndo(HttpResult r, String txnId) {
        if (r == null) {
            return MoneyStepResult.transientFailure("null HTTP result");
        }
        if (r.status() == 404) {
            return MoneyStepResult.noOp("original txn absent");
        }
        if (r.is2xx()) {
            String id = txnId == null || txnId.isBlank() ? "UNDO" : txnId;
            return MoneyStepResult.committed(id, id);
        }
        if (r.isBusinessError()) {
            String body = snippet(r.body()).toLowerCase();
            if (body.contains("already") || body.contains("undone") || body.contains("revers")) {
                return MoneyStepResult.noOp("already reversed");
            }
            return MoneyStepResult.businessReject("HTTP " + r.status() + ": " + snippet(r.body()));
        }
        String detail = r.transportError() ? r.transportDetail() : "HTTP " + r.status();
        return MoneyStepResult.transientFailure("peer-down: " + detail);
    }

    /**
     * Map GET {@code /savingsaccounts/{id}?associations=summary} onto a read-only {@link BalanceQuery}.
     * 4xx → NOT_FOUND (do not flip live); transport/5xx → TRANSIENT.
     */
    public static BalanceQuery ofBalance(HttpResult r, String tenantId, String accountId) {
        if (r == null) {
            return BalanceQuery.transientFailure("null HTTP result");
        }
        if (r.isPeerDown()) {
            String detail = r.transportError() ? r.transportDetail() : "HTTP " + r.status();
            return BalanceQuery.transientFailure("peer-down: " + detail);
        }
        if (!r.is2xx()) {
            return BalanceQuery.notFound("HTTP " + r.status() + ": " + snippet(r.body()));
        }
        try {
            JsonNode root = JSON.readTree(r.body());
            JsonNode summary = root.path("summary");
            String balText = summary.path("accountBalance").asText("0");
            if (balText.isBlank()) {
                balText = "0";
            }
            BigDecimal bal = new BigDecimal(balText);
            String availText = summary.path("availableBalance").asText("");
            BigDecimal available = availText.isBlank() ? bal : new BigDecimal(availText);
            String ccy = root.path("currency").path("code").asText("ETB");
            if (ccy.isBlank() || ccy.length() != 3) {
                ccy = "ETB";
            }
            Money ledger = new Money(bal, ccy);
            Money avail = new Money(available, ccy);
            return BalanceQuery.found(new AccountBalance(tenantId, accountId, avail, ledger, "FINERACT"));
        } catch (Exception e) {
            return BalanceQuery.transientFailure("parse: " + e.getMessage());
        }
    }

    public static Optional<String> findTxnIdByExternalId(String body, String externalId) {
        if (body == null || externalId == null || externalId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode txns = root.path("transactions");
            if (!txns.isArray()) {
                txns = root.isArray() ? root : null;
            }
            if (txns == null || !txns.isArray()) {
                return Optional.empty();
            }
            for (JsonNode t : txns) {
                String ext = t.path("externalId").asText("");
                if (externalId.equals(ext)) {
                    if (t.hasNonNull("id")) {
                        return Optional.of(t.get("id").asText());
                    }
                    if (t.hasNonNull("resourceId")) {
                        return Optional.of(t.get("resourceId").asText());
                    }
                }
            }
        } catch (Exception ignore) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.strip();
        return s.length() <= 240 ? s : s.substring(0, 240);
    }
}
