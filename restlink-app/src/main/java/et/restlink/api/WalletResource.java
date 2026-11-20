package et.restlink.api;

import et.restlink.payment.PaymentService;
import et.restlink.payment.PaymentService.Start;
import et.restlink.persist.WalletControl;
import et.restlink.spi.model.AccountBalance;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.wallet.WalletService;
import et.restlink.wallet.WalletService.HistoryPage;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase-1 wallet API. Money moves through Temporal payment workflows — Temporal down ⇒ 503,
 * never a direct ledger post. Poll {@code GET /payments/{paymentId}} for durable status.
 */
@Path("/wallet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WalletResource {

    @Inject
    WalletService wallet;
    @Inject
    PaymentService payments;

    @ConfigProperty(name = "restlink.admin.key", defaultValue = AdminAuth.UNSET_DEFAULT)
    String adminKey;

    public record OpenReq(String tenantId, String customerExternalId, String currency) {}
    public record MoveReq(String tenantId, String accountId, String amount, String currency) {}
    public record TransferReq(String tenantId, String fromAccount, String toAccount,
                              String amount, String currency) {}
    public record LimitsReq(String perTxn, String daily) {}

    @POST
    @Path("/open")
    public Response open(OpenReq r) {
        String requestId = wallet.openWallet(r.tenantId(), r.customerExternalId(), r.currency());
        return Response.accepted(Map.of("requestId", requestId)).build();
    }

    @POST
    @Path("/cash-in")
    public Response cashIn(@HeaderParam("Idempotency-Key") String key, MoveReq r) {
        return respond(payments.cashIn(r.tenantId(), r.accountId(), r.amount(), r.currency(), key));
    }

    @POST
    @Path("/cash-out")
    public Response cashOut(@HeaderParam("Idempotency-Key") String key, MoveReq r) {
        return respond(payments.cashOut(r.tenantId(), r.accountId(), r.amount(), r.currency(), key));
    }

    @POST
    @Path("/transfer")
    public Response transfer(@HeaderParam("Idempotency-Key") String key, TransferReq r) {
        return respond(payments.transfer(r.tenantId(), r.fromAccount(), r.toAccount(),
                r.amount(), r.currency(), key));
    }

    @GET
    @Path("/{tenantId}/{accountId}/balance")
    public Response balance(@PathParam("tenantId") String tenantId,
                            @PathParam("accountId") String accountId) {
        BalanceQuery q = wallet.queryBalance(tenantId, accountId);
        Map<String, Object> control = wallet.controlView(tenantId, accountId);
        return switch (q.kind()) {
            case FOUND -> {
                AccountBalance s = q.snapshot();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("tenantId", s.tenantId());
                body.put("accountId", s.accountId());
                body.put("available", s.available().amount().toPlainString());
                body.put("ledger", s.ledger().amount().toPlainString());
                body.put("currency", s.available().currency());
                body.put("provider", s.provider());
                body.put("frozen", control.get("frozen"));
                body.put("limits", control.get("limits"));
                yield Response.ok(body).build();
            }
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "NOT_FOUND", "message",
                            q.message() == null ? "account not found" : q.message())).build();
            case TRANSIENT -> Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "CORE_UNAVAILABLE", "message",
                            q.message() == null ? "peer down" : q.message())).build();
            case UNSUPPORTED -> Response.status(Response.Status.NOT_IMPLEMENTED)
                    .entity(Map.of("error", "UNSUPPORTED", "message",
                            q.message() == null ? "queryBalance not implemented" : q.message())).build();
        };
    }

    @GET
    @Path("/{tenantId}/{accountId}/history")
    public Response history(@PathParam("tenantId") String tenantId,
                            @PathParam("accountId") String accountId,
                            @QueryParam("offset") @DefaultValue("0") int offset,
                            @QueryParam("limit") @DefaultValue("50") int limit) {
        HistoryPage page = wallet.history(tenantId, accountId, offset, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", page.tenantId());
        body.put("accountId", page.accountId());
        body.put("offset", page.offset());
        body.put("limit", page.limit());
        body.put("total", page.total());
        body.put("items", page.items());
        return Response.ok(body).build();
    }

    @POST
    @Path("/{tenantId}/{accountId}/freeze")
    public Response freeze(@HeaderParam("X-Restlink-Admin-Key") String key,
                           @PathParam("tenantId") String tenantId,
                           @PathParam("accountId") String accountId) {
        Response denied = requireAdmin(key);
        if (denied != null) return denied;
        return controlOk(wallet.freeze(tenantId, accountId, true));
    }

    @POST
    @Path("/{tenantId}/{accountId}/unfreeze")
    public Response unfreeze(@HeaderParam("X-Restlink-Admin-Key") String key,
                             @PathParam("tenantId") String tenantId,
                             @PathParam("accountId") String accountId) {
        Response denied = requireAdmin(key);
        if (denied != null) return denied;
        return controlOk(wallet.freeze(tenantId, accountId, false));
    }

    @PUT
    @Path("/{tenantId}/{accountId}/limits")
    public Response limits(@HeaderParam("X-Restlink-Admin-Key") String key,
                           @PathParam("tenantId") String tenantId,
                           @PathParam("accountId") String accountId,
                           LimitsReq r) {
        Response denied = requireAdmin(key);
        if (denied != null) return denied;
        try {
            BigDecimal perTxn = parseLimit(r == null ? null : r.perTxn());
            BigDecimal daily = parseLimit(r == null ? null : r.daily());
            return controlOk(wallet.setLimits(tenantId, accountId, perTxn, daily));
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_REQUEST", "message", e.getMessage())).build();
        }
    }

    /** @deprecated Prefer {@code GET /payments/{paymentId}}. Kept for pending non-money requests. */
    @GET
    @Path("/response/{requestId}")
    public Response response(@PathParam("requestId") String requestId) {
        Optional<Map<String, Object>> pay = payments.status(requestId);
        if (pay.isPresent()) return Response.ok(pay.get()).build();
        var pr = wallet.response(requestId);
        if (pr.isEmpty()) return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("requestId", requestId, "state", "UNKNOWN")).build();
        var p = pr.get();
        return Response.ok(Map.of(
                "requestId", p.requestId(),
                "state", p.state().name(),
                "provider", p.provider(),
                "ownerNode", p.ownerNode(),
                "result", p.resultJson() == null ? "" : p.resultJson()
        )).build();
    }

    private Response requireAdmin(String key) {
        String reason = AdminAuth.denyReason(adminKey, key);
        if (reason == null) return null;
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", reason)).build();
    }

    private static Response controlOk(WalletControl c) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("perTxn", c.perTxnLimit == null ? null : c.perTxnLimit.toPlainString());
        limits.put("daily", c.dailyLimit == null ? null : c.dailyLimit.toPlainString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", c.tenantId);
        body.put("accountId", c.accountId);
        body.put("frozen", c.frozen);
        body.put("limits", limits);
        return Response.ok(body).build();
    }

    private static BigDecimal parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit is not a valid decimal");
        }
    }

    private static Response respond(Start s) {
        return switch (s) {
            case Start.Accepted a -> Response.accepted(Map.of(
                    "result", "ACCEPTED", "paymentId", a.paymentId())).build();
            case Start.Replay r -> Response.ok(Map.of(
                    "result", "REPLAY",
                    "paymentId", r.paymentId(),
                    "state", r.state(),
                    "coreTxnId", r.coreTxnId() == null ? "" : r.coreTxnId())).build();
            case Start.Conflict c -> Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "IDEMPOTENCY_CONFLICT", "message", c.message())).build();
            case Start.Unavailable u -> Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "TEMPORAL_UNAVAILABLE", "message", u.message())).build();
            case Start.Invalid i -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_REQUEST", "message", i.message())).build();
            case Start.Frozen f -> Response.status(423)
                    .entity(Map.of("error", "ACCOUNT_FROZEN", "message", f.message())).build();
            case Start.LimitExceeded l -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "LIMIT_EXCEEDED", "message", l.message())).build();
            case Start.KycBlocked k -> Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "KYC_REQUIRED", "message", k.message())).build();
        };
    }
}
