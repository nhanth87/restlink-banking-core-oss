package et.restlink.ra.temenos;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;
import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.ProviderCapabilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port adapter for {@link TemenosResourceAdaptor}. RA name: {@code temenos-ra}.
 * Implements {@link CoreBankingPort} so the app treats it as a swappable core provider.
 */
public final class TemenosRaEndpoint implements RaEndpointPort, RaCommandPort, CoreBankingPort {

    private static final Logger LOG = LogManager.getLogger(TemenosRaEndpoint.class);

    private final TemenosResourceAdaptor delegate;

    public TemenosRaEndpoint(TemenosResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public TemenosRaEndpoint() {
        this(new TemenosResourceAdaptor());
    }

    public void setConfig(TemenosRaConfig config) {
        delegate.setConfig(config);
    }

    public TemenosResourceAdaptor delegate() {
        return delegate;
    }

    @Override
    public String getRaName() {
        return "temenos-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        delegate.setBootstrapPort(bootstrap);
        delegate.raActive();
        LOG.info("Temenos RA endpoint activated");
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
            LOG.warn("temenos-ra unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    @Override
    public CoreProvider provider() {
        return CoreProvider.TEMENOS;
    }

    @Override
    public void submit(CoreBankingCommand command) {
        delegate.sendOutbound(command);
    }

    @Override
    public boolean coreLive() {
        return delegate.live();
    }

    /** Scaffold — no proven money capabilities until OFS transfer/lookup/reverse land. */
    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.of();
    }

    /** Fail-closed: never invent an OFS money post. Saga sees empty caps and will not call this. */
    @Override
    public MoneyStepResult executeMoney(CoreBankingCommand command) {
        return MoneyStepResult.businessReject("ra-temenos scaffold: money not implemented (fail-closed)");
    }

    public void heartbeat() {
        delegate.heartbeat();
    }
}
