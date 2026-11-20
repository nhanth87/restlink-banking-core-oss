package et.restlink.wallet;

import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;
import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.command.CreateCustomer;
import et.restlink.spi.command.OpenAccount;
import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.command.QueryBalance;
import et.restlink.spi.command.ReverseTransaction;
import et.restlink.spi.command.TransferFunds;
import et.restlink.spi.events.AccountOpenedEvent;
import et.restlink.spi.events.BalanceEvent;
import et.restlink.spi.events.CoreBankingEvent;
import et.restlink.spi.events.CoreErrorEvent;
import et.restlink.spi.events.CustomerCreatedEvent;
import et.restlink.spi.events.TransactionPostedEvent;
import et.restlink.spi.model.AccountBalance;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.spi.model.Money;
import et.restlink.spi.payment.MoneyOutcomeKind;
import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.ProviderCapabilities;
import et.restlink.spi.payment.ProviderCapability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Phase-1 native core: double-entry wallet ledger. Advertises {@link ProviderCapability#NATIVE_ATOMIC_TRANSFER}
 * so Temporal uses a single JTA transfer Activity instead of split debit/credit submits.
 */
public final class WalletLedgerProvider implements CoreBankingPort {

    private static final Logger LOG = LoggerFactory.getLogger(WalletLedgerProvider.class);
    private static final String PROVIDER = CoreProvider.WALLET_LEDGER.name();
    private static final ProviderCapabilities CAPS = ProviderCapabilities.of(
            ProviderCapability.NATIVE_ATOMIC_TRANSFER,
            ProviderCapability.POST_LEG,
            ProviderCapability.LOOKUP_BY_IDEMPOTENCY,
            ProviderCapability.REVERSE);

    private final WalletLedgerRepository repo;
    private volatile Consumer<CoreBankingEvent> eventSink = e -> {};

    public WalletLedgerProvider(WalletLedgerRepository repo) {
        this.repo = repo;
    }

    public void setEventSink(Consumer<CoreBankingEvent> sink) {
        if (sink != null) this.eventSink = sink;
    }

    public WalletLedgerRepository repository() {
        return repo;
    }

    @Override public CoreProvider provider() { return CoreProvider.WALLET_LEDGER; }

    @Override public boolean coreLive() { return repo.live(); }

    @Override public ProviderCapabilities capabilities() { return CAPS; }

    @Override
    public MoneyStepResult executeMoney(CoreBankingCommand command) {
        if (command == null) {
            return MoneyStepResult.businessReject("null command");
        }
        if (!repo.live()) {
            return MoneyStepResult.transientFailure("wallet datastore down");
        }
        try {
            return switch (command) {
                case PostTransaction p -> {
                    String txnId = repo.post(p);
                    yield MoneyStepResult.committed(txnId, txnId);
                }
                case TransferFunds t -> repo.transfer(t.tenantId(), t.fromAccountId(), t.toAccountId(),
                        t.amount(), t.narrative(), t.idempotencyKey(), null, t.requestId());
                case ReverseTransaction r -> repo.reverse(r.tenantId(), r.accountId(), r.originalCoreTxnId(),
                        r.amount(), r.narrative(), r.idempotencyKey(), null, r.requestId());
                default -> MoneyStepResult.businessReject("not a money command: " + command.getClass().getSimpleName());
            };
        } catch (IllegalStateException biz) {
            return MoneyStepResult.businessReject(biz.getMessage());
        } catch (RuntimeException e) {
            LOG.error("wallet executeMoney failed req={} type={}: {}",
                    command.requestId(), command.getClass().getSimpleName(), e.toString());
            return MoneyStepResult.transientFailure(e.getMessage());
        }
    }

    @Override
    public Optional<String> lookupByIdempotency(String tenantId, String accountId, String idempotencyKey) {
        return repo.findByIdempotencyKey(tenantId, idempotencyKey);
    }

    @Override
    public BalanceQuery queryBalance(String tenantId, String accountId) {
        if (!repo.live()) {
            return BalanceQuery.transientFailure("wallet datastore down");
        }
        try {
            Money bal = repo.availableBalance(tenantId, accountId);
            return BalanceQuery.found(new AccountBalance(tenantId, accountId, bal, bal, PROVIDER));
        } catch (IllegalStateException e) {
            return BalanceQuery.notFound(e.getMessage());
        }
    }

    @Override public void submit(CoreBankingCommand command) {
        try {
            CoreBankingEvent result = switch (command) {
                case CreateCustomer c -> new CustomerCreatedEvent(c.requestId(), PROVIDER, c.tenantId(),
                        c.externalKycId() == null ? c.mobileNo() : c.externalKycId(), true, "wallet cif");
                case OpenAccount o -> {
                    String acct = repo.openAccount(o.tenantId(), o.customerExternalId(), o.product(), o.currency());
                    yield new AccountOpenedEvent(o.requestId(), PROVIDER, o.tenantId(), acct,
                            o.product(), o.currency(), true, "wallet account");
                }
                case PostTransaction p -> {
                    String txnId = repo.post(p);
                    yield new TransactionPostedEvent(p.requestId(), PROVIDER, p.tenantId(),
                            p.accountId(), txnId, true, "posted");
                }
                case TransferFunds t -> {
                    MoneyStepResult r = repo.transfer(t.tenantId(), t.fromAccountId(), t.toAccountId(),
                            t.amount(), t.narrative(), t.idempotencyKey(), null, t.requestId());
                    if (!r.committed()) {
                        yield new CoreErrorEvent(t.requestId(), PROVIDER, t.tenantId(),
                                "TransferFunds", r.message());
                    }
                    yield new TransactionPostedEvent(t.requestId(), PROVIDER, t.tenantId(),
                            t.fromAccountId(), r.coreTxnId(), true, "transferred");
                }
                case ReverseTransaction r -> {
                    MoneyStepResult out = repo.reverse(r.tenantId(), r.accountId(), r.originalCoreTxnId(),
                            r.amount(), r.narrative(), r.idempotencyKey(), null, r.requestId());
                    if (!out.committed() && out.kind() != et.restlink.spi.payment.MoneyOutcomeKind.NO_OP) {
                        yield new CoreErrorEvent(r.requestId(), PROVIDER, r.tenantId(),
                                "ReverseTransaction", out.message());
                    }
                    yield new TransactionPostedEvent(r.requestId(), PROVIDER, r.tenantId(),
                            r.accountId(), out.coreTxnId() == null ? "NOOP" : out.coreTxnId(), true, "reversed");
                }
                case QueryBalance q -> {
                    Money bal = repo.availableBalance(q.tenantId(), q.accountId());
                    yield new BalanceEvent(q.requestId(), PROVIDER, q.tenantId(), q.accountId(),
                            bal, bal, true, "wallet balance");
                }
            };
            eventSink.accept(result);
        } catch (RuntimeException e) {
            LOG.error("wallet submit failed req={} type={}: {}",
                    command.requestId(), command.getClass().getSimpleName(), e.toString());
            eventSink.accept(new CoreErrorEvent(command.requestId(), PROVIDER, command.tenantId(),
                    command.getClass().getSimpleName(), e.getMessage()));
        }
    }
}
