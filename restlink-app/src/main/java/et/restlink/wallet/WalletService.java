package et.restlink.wallet;

import et.restlink.core.CoreBankingRouter;
import et.restlink.core.CoreBankingService;
import et.restlink.core.PendingRequest;
import et.restlink.payment.PaymentSagaRepository;
import et.restlink.persist.PaymentSagaEntity;
import et.restlink.persist.WalletControl;
import et.restlink.profile.CustomerDirectory;
import et.restlink.spi.command.OpenAccount;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.wallet.WalletGate.Snapshot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-money wallet ops (open / balance / history / freeze / limits) go through this service.
 * Cash-in / cash-out / transfer are owned by {@link et.restlink.payment.PaymentService} (Temporal).
 */
@ApplicationScoped
public class WalletService {

    private static final int HISTORY_MAX = 200;
    private static final int HISTORY_DEFAULT = 50;

    @Inject
    CoreBankingService core;
    @Inject
    CoreBankingRouter router;
    @Inject
    CustomerDirectory customers;
    @Inject
    WalletControlService controls;
    @Inject
    PaymentSagaRepository sagas;

    public String openWallet(String tenantId, String customerExternalId, String currency) {
        var handle = customers.ensure(tenantId, customerExternalId,
                new CustomerDirectory.CustomerSpec(null, null, null, customerExternalId,
                        null, currency, "ACTIVE"));
        String requestId = newRequestId();
        handle.cmp().recordRequest(requestId);
        core.submit(new OpenAccount(requestId, tenantId, customerExternalId, "WALLET", currency));
        return requestId;
    }

    public BalanceQuery queryBalance(String tenantId, String accountId) {
        if (blank(tenantId) || blank(accountId)) {
            return BalanceQuery.notFound("tenantId and accountId required");
        }
        try {
            return router.portFor(tenantId).queryBalance(tenantId, accountId);
        } catch (IllegalStateException e) {
            return BalanceQuery.transientFailure(e.getMessage());
        }
    }

    public Map<String, Object> controlView(String tenantId, String accountId) {
        Snapshot snap = controls.snapshot(tenantId, accountId);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("perTxn", decimal(snap.perTxnLimit()));
        limits.put("daily", decimal(snap.dailyLimit()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frozen", snap.frozen());
        out.put("limits", limits);
        return out;
    }

    public HistoryPage history(String tenantId, String accountId, int offset, int limit) {
        int off = Math.max(0, offset);
        int lim = limit <= 0 ? HISTORY_DEFAULT : Math.min(limit, HISTORY_MAX);
        List<PaymentSagaEntity> rows = sagas.listForAccount(tenantId, accountId, off, lim);
        long total = sagas.countForAccount(tenantId, accountId);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (PaymentSagaEntity e : rows) {
            items.add(historyItem(e, accountId));
        }
        return new HistoryPage(tenantId, accountId, off, lim, total, items);
    }

    public WalletControl freeze(String tenantId, String accountId, boolean frozen) {
        return controls.freeze(tenantId, accountId, frozen);
    }

    public WalletControl setLimits(String tenantId, String accountId, BigDecimal perTxn, BigDecimal daily) {
        if (perTxn != null && perTxn.signum() <= 0) {
            throw new IllegalArgumentException("perTxn must be positive or omitted");
        }
        if (daily != null && daily.signum() <= 0) {
            throw new IllegalArgumentException("daily must be positive or omitted");
        }
        return controls.setLimits(tenantId, accountId, perTxn, daily);
    }

    public Optional<PendingRequest> response(String requestId) {
        return core.lookup(requestId);
    }

    public record HistoryPage(String tenantId, String accountId, int offset, int limit, long total,
                              List<Map<String, Object>> items) {}

    private static Map<String, Object> historyItem(PaymentSagaEntity e, String accountId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", e.paymentId);
        m.put("operation", e.operation);
        m.put("direction", direction(e, accountId));
        m.put("amount", e.amount == null ? "" : e.amount.toPlainString());
        m.put("currency", e.currency);
        m.put("state", e.state);
        m.put("coreTxnId", e.coreTxnId == null ? "" : e.coreTxnId);
        m.put("createdMs", e.createdMs);
        return m;
    }

    private static String direction(PaymentSagaEntity e, String accountId) {
        if (accountId != null && accountId.equals(e.toAccountId) && !accountId.equals(e.fromAccountId)) {
            return "CREDIT";
        }
        if (accountId != null && accountId.equals(e.fromAccountId)) {
            return "DEBIT";
        }
        return "";
    }

    private static String decimal(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String newRequestId() {
        return "REQ-" + UUID.randomUUID();
    }
}
