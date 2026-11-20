package et.restlink.spi;

import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.ProviderCapabilities;

import java.util.Optional;

/**
 * The swappable core-banking contract. An SBB/service submits a {@link CoreBankingCommand}; the
 * bound RA adaptor ({@code ra-fineract} or {@code ra-temenos}) executes it against the external
 * engine and fires a {@code CoreBankingEvent} back into the SLEE.
 *
 * <p>This is the seam that makes Fineract vs Temenos a per-tenant config choice, not a code change.
 * Implemented by each RA's endpoint (which also implements the micro-jainslee 3-port
 * {@code RaEndpointPort} + {@code RaCommandPort}).
 *
 * <p>Temporal Activities call through this port — never HTTP/OFS directly from Workflow code.
 * Money must use {@link #executeMoney}; never fall back to another provider.
 */
public interface CoreBankingPort {

    /** Which engine this port talks to. */
    CoreProvider provider();

    /** Submit a command to the external core engine. Result is delivered asynchronously as an event. */
    void submit(CoreBankingCommand command);

    /**
     * Peer/component truth — {@code true} only after a real engine probe (health/auth/OFS round-trip)
     * succeeded. Never return {@code true} merely because the RA started or a socket is open.
     */
    boolean coreLive();

    /**
     * Money capabilities. Cross-provider Saga is fail-closed unless reserve/reverse/reconcile
     * capabilities are advertised and proven.
     */
    default ProviderCapabilities capabilities() {
        return ProviderCapabilities.of();
    }

    /**
     * Synchronous money step for Temporal Activities. Default is fail-closed (no engine).
     *
     * <p>Must be idempotent on the command's idempotency key. Map 4xx → {@code BUSINESS_REJECT}
     * (do not flip {@code coreLive}); transport/5xx → {@code TRANSIENT} so Temporal retries with
     * the same key; never throw for a business reject (that skips saga compensation).
     */
    default MoneyStepResult executeMoney(CoreBankingCommand command) {
        return MoneyStepResult.businessReject(provider() + " has no money engine");
    }

    /**
     * Read-only lookup for saga reconcile. Never posts. {@code accountId} may be null for engines
     * that key only on {@code (tenant, idempotencyKey)}.
     */
    default Optional<String> lookupByIdempotency(String tenantId, String accountId, String idempotencyKey) {
        return Optional.empty();
    }

    /**
     * Synchronous balance enquiry. Read-only — never posts. Default is unsupported (scaffold).
     * Peer-down → {@code TRANSIENT}; missing account → {@code NOT_FOUND}.
     */
    default BalanceQuery queryBalance(String tenantId, String accountId) {
        return BalanceQuery.unsupported(provider() + " has no queryBalance");
    }
}
