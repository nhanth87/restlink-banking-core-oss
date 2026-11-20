package et.restlink.core;

import et.restlink.ra.fineract.FineractRaEndpoint;
import et.restlink.ra.temenos.TemenosRaEndpoint;
import et.restlink.spi.CoreBankingPort;

import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Recovers {@code coreLive} after peer-down so the money circuit can close. Probe is the RA's own
 * health/OFS round-trip — never "RA still started".
 */
@ApplicationScoped
public class CoreLinkHeartbeat {

    @Inject
    CoreBankingRouter router;

    @Scheduled(every = "20s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void beat() {
        for (CoreBankingPort port : router.ports().values()) {
            switch (port) {
                case FineractRaEndpoint f -> f.heartbeat();
                case TemenosRaEndpoint t -> t.heartbeat();
                default -> { }
            }
        }
    }
}
