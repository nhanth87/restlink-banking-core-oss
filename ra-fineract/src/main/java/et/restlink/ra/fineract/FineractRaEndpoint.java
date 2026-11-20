package et.restlink.ra.fineract;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;
import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.ProviderCapabilities;
import et.restlink.spi.payment.ProviderCapability;

import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port adapter for {@link FineractResourceAdaptor}. RA name: {@code fineract-ra}.
 * Also implements {@link CoreBankingPort} so the app can treat it as a swappable core provider.
 */
public final class FineractRaEndpoint implements RaEndpointPort, RaCommandPort, CoreBankingPort {

    private static final Logger LOG = LogManager.getLogger(FineractRaEndpoint.class);

    private final FineractResourceAdaptor delegate;

    public FineractRaEndpoint(FineractResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public FineractRaEndpoint() {
        this(new FineractResourceAdaptor());
    }

    public void setConfig(FineractRaConfig config) {
        delegate.setConfig(config);
    }

    public FineractResourceAdaptor delegate() {
        return delegate;
    }

    @Override
    public String getRaName() {
        return "fineract-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        delegate.setBootstrapPort(bootstrap);
        delegate.raActive();
        LOG.info("Fineract RA endpoint activated");
    }

    @Override
    public void deactivate() {
        try {
            delegate.raInactive();
        } catch (RuntimeException e) {
            LOG.warn("raInactive failed", e);
        }
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof CoreBankingCommand c) {
            delegate.sendOutbound(c);
        } else {
            LOG.warn("fineract-ra unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    // --- CoreBankingPort ---

    @Override
    public CoreProvider provider() {
        return CoreProvider.FINERACT;
    }

    @Override
    public void submit(CoreBankingCommand command) {
        delegate.sendOutbound(command);
    }

    @Override
    public boolean coreLive() {
        return delegate.live();
    }

    /**
     * Cash-in/out via POST_LEG (deposit/withdrawal + Idempotency-Key + lookup). Transfer and
     * distributed Saga stay fail-closed until native accounttransfers / reserve are proven on wire.
     */
    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.of(
                ProviderCapability.POST_LEG,
                ProviderCapability.LOOKUP_BY_IDEMPOTENCY,
                ProviderCapability.REVERSE);
    }

    @Override
    public MoneyStepResult executeMoney(CoreBankingCommand command) {
        return delegate.executeMoney(command);
    }

    @Override
    public Optional<String> lookupByIdempotency(String tenantId, String accountId, String idempotencyKey) {
        return delegate.lookupByIdempotency(tenantId, accountId, idempotencyKey);
    }

    @Override
    public BalanceQuery queryBalance(String tenantId, String accountId) {
        return delegate.queryBalance(tenantId, accountId);
    }

    public void heartbeat() {
        delegate.heartbeat();
    }
}
