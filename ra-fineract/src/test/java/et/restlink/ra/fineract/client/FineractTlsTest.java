package et.restlink.ra.fineract.client;

import et.restlink.ra.fineract.FineractRaConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FineractTlsTest {

    @Test
    void insecureTlsWithoutLabFlagFailsClosed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FineractTls.requireLabTrustAll(false));
        assertTrue(ex.getMessage().contains("allow-insecure-tls-lab"));
    }

    @Test
    void constructingClientWithInsecureTlsAndNoLabFlagThrows() {
        FineractRaConfig cfg = new FineractRaConfig()
                .baseUrl("https://127.0.0.1:8443/fineract-provider/api/v1")
                .insecureTls(true)
                .allowInsecureTlsLab(false);
        assertThrows(IllegalStateException.class, () -> new FineractRestClient(cfg));
    }

    @Test
    void defaultConfigDoesNotEnableTrustAll() {
        FineractRaConfig cfg = new FineractRaConfig().baseUrl("http://127.0.0.1:8080/fineract-provider/api/v1");
        assertDoesNotThrow(() -> new FineractRestClient(cfg).close());
    }

    @Test
    void labDoubleGateAllowsTrustAllContext() {
        assertDoesNotThrow(() -> FineractTls.requireLabTrustAll(true));
    }
}
