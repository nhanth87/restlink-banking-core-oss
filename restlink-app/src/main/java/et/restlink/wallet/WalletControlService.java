package et.restlink.wallet;

import et.restlink.payment.PaymentSagaRepository;
import et.restlink.persist.WalletControl;
import et.restlink.spi.payment.PaymentOperation;
import et.restlink.wallet.WalletGate.Decision;
import et.restlink.wallet.WalletGate.Snapshot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Restlink-owned freeze/limits. New money is rejected at the HTTP edge; in-flight Temporal sagas
 * still complete (do not abort a posted leg).
 */
@ApplicationScoped
public class WalletControlService {

    private final PaymentSagaRepository sagas;
    private final Clock clock;

    @Inject
    public WalletControlService(PaymentSagaRepository sagas) {
        this(sagas, Clock.systemUTC());
    }

    WalletControlService(PaymentSagaRepository sagas, Clock clock) {
        this.sagas = sagas;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Optional<WalletControl> get(String tenantId, String accountId) {
        if (blank(tenantId) || blank(accountId)) {
            return Optional.empty();
        }
        return WalletControl.find(tenantId, accountId);
    }

    public Snapshot snapshot(String tenantId, String accountId) {
        return get(tenantId, accountId)
                .map(c -> new Snapshot(c.frozen, c.perTxnLimit, c.dailyLimit))
                .orElseGet(Snapshot::open);
    }

    public Decision decide(PaymentOperation op, String tenantId, String from, String to, BigDecimal amount) {
        if (op == null) {
            return new WalletGate.Allow();
        }
        return switch (op) {
            case CASH_IN -> checkAccount(tenantId, to, amount, false);
            case CASH_OUT -> checkAccount(tenantId, from, amount, true);
            case TRANSFER -> {
                Decision fromDec = checkAccount(tenantId, from, amount, true);
                if (fromDec.blocked()) {
                    yield fromDec;
                }
                yield checkAccount(tenantId, to, amount, false);
            }
        };
    }

    @Transactional
    public WalletControl freeze(String tenantId, String accountId, boolean frozen) {
        WalletControl row = loadOrCreate(tenantId, accountId);
        row.frozen = frozen;
        row.updatedMs = clock.millis();
        return row;
    }

    @Transactional
    public WalletControl setLimits(String tenantId, String accountId, BigDecimal perTxn, BigDecimal daily) {
        WalletControl row = loadOrCreate(tenantId, accountId);
        row.perTxnLimit = perTxn;
        row.dailyLimit = daily;
        row.updatedMs = clock.millis();
        return row;
    }

    private Decision checkAccount(String tenantId, String accountId, BigDecimal amount, boolean debit) {
        Snapshot snap = snapshot(tenantId, accountId);
        BigDecimal spent = debit
                ? sagas.sumCompletedDebitsSince(tenantId, accountId, startOfUtcDayMs())
                : BigDecimal.ZERO;
        return WalletGate.check(accountId, snap, amount, spent, debit);
    }

    private WalletControl loadOrCreate(String tenantId, String accountId) {
        if (blank(tenantId) || blank(accountId)) {
            throw new IllegalArgumentException("tenantId and accountId required");
        }
        Optional<WalletControl> existing = WalletControl.find(tenantId, accountId);
        if (existing.isPresent()) {
            return existing.get();
        }
        WalletControl row = new WalletControl();
        row.tenantId = tenantId;
        row.accountId = accountId;
        row.frozen = false;
        row.updatedMs = clock.millis();
        row.persist();
        return row;
    }

    long startOfUtcDayMs() {
        return Instant.now(clock).truncatedTo(ChronoUnit.DAYS).toEpochMilli();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
