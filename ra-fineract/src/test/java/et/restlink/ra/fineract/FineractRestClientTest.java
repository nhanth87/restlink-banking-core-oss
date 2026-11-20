package et.restlink.ra.fineract;

import et.restlink.ra.fineract.client.FineractRestClient;
import et.restlink.ra.fineract.client.FineractRestClient.HttpResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FineractRestClientTest {

    @Test
    void idempotencyKeyIsStableForSameSeedAndValidUuidLayout() {
        String seed = "tenant-a:deposit:acc-1:ext-42";
        String k1 = FineractRestClient.idempotencyKey(seed);
        String k2 = FineractRestClient.idempotencyKey(seed);
        assertEquals(k1, k2, "same seed must yield same key so a retry is de-duplicated by Fineract");
        // Fineract validates keys against a UUID layout.
        assertEquals(k1, UUID.fromString(k1).toString());
    }

    @Test
    void idempotencyKeyDiffersForDifferentSeeds() {
        assertNotEquals(
                FineractRestClient.idempotencyKey("tenant-a:deposit:acc-1"),
                FineractRestClient.idempotencyKey("tenant-a:withdrawal:acc-1"));
    }

    @Test
    void businessErrorIsNotPeerDown() {
        HttpResult r = new HttpResult(400, "{\"errors\":[]}", false, null);
        assertTrue(r.isBusinessError());
        assertFalse(r.isPeerDown(), "a 400 validation error must NOT mark Fineract down (link-truth)");
    }

    @Test
    void serverErrorAndTransportArePeerDown() {
        assertTrue(new HttpResult(503, "", false, null).isPeerDown());
        assertTrue(new HttpResult(0, null, true, "connect timeout").isPeerDown());
    }
}
