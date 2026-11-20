package et.restlink.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.annotations.SbbAnnotation;

import et.restlink.boot.Restlink;
import et.restlink.spi.events.CoreBankingEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLEE-facing completion handler for the Phase-2 RA path ({@code ra-fineract} / {@code ra-temenos}).
 * When a core RA fires a {@link CoreBankingEvent} into the SLEE, this SBB drives it into the N–N
 * correlation store via {@link et.restlink.core.CoreBankingService#onEvent}. Safe on any node — the
 * pending entry is replicated (DIST_SYNC), so completion works even if the event lands away from the
 * caller.
 *
 * <p>Phase 1 (native WALLET_LEDGER) does not go through the SLEE — the provider calls
 * {@code CoreBankingService.onEvent} directly via its event sink — but the same guarantees apply.
 */
@SbbAnnotation(name = "core-banking-sbb")
public class CoreBankingSbb implements Sbb {

    private static final Logger LOG = LoggerFactory.getLogger(CoreBankingSbb.class);

    /** Event handler: {@code CoreBankingEvent} delivered by a core RA. */
    public void onCoreBankingEvent(CoreBankingEvent event, ActivityContextInterface aci) {
        LOG.debug("SBB core event req={} provider={} type={}",
                event.requestId(), event.provider(), event.getClass().getSimpleName());
        Restlink.core().onEvent(event);
    }
}
