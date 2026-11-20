package et.restlink.boot;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;

import et.restlink.core.CoreBankingService;
import et.restlink.spi.events.CoreBankingEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal {@link RaBootstrapPort} that forwards a core RA's fired {@link CoreBankingEvent}s straight
 * into the N–N correlation store ({@link CoreBankingService#onEvent}). This is the P0-lab wiring so a
 * FINERACT/TEMENOS tenant works end-to-end without the full micro-jainslee container.
 *
 * <p>Phase-2 production routes RA events through the SLEE event router + {@code CoreBankingSbb} instead;
 * the correlation guarantees are identical because the pending store is cluster-replicated either way.
 */
public final class RaEventBridge implements RaBootstrapPort {

    private static final Logger LOG = LoggerFactory.getLogger(RaEventBridge.class);

    private final CoreBankingService core;

    public RaEventBridge(CoreBankingService core) {
        this.core = core;
    }

    @Override
    public ActivityHandle createActivityHandle(String id) {
        return () -> id;
    }

    @Override
    public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
        if (event instanceof CoreBankingEvent cbe) {
            core.onEvent(cbe);
        } else {
            LOG.debug("ignoring non-core event {}", event == null ? "null" : event.getClass().getName());
        }
    }
}
