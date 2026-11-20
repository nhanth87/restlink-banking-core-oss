package et.restlink.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAuditTest {

    @Test
    void mutatingMethodsOnly() {
        assertTrue(ApiAudit.mutating("POST"));
        assertTrue(ApiAudit.mutating("put"));
        assertFalse(ApiAudit.mutating("GET"));
        assertFalse(ApiAudit.mutating("HEAD"));
    }

    @Test
    void actorNeverContainsRawSecret() {
        String actor = ApiAudit.actorId(false, "super-secret-api-key");
        assertTrue(actor.startsWith("api:"));
        assertEquals(16, actor.length());
        assertFalse(actor.contains("super-secret"));
        assertEquals("admin", ApiAudit.actorId(true, "super-secret-api-key"));
        assertEquals("anonymous", ApiAudit.actorId(false, null));
    }

    @Test
    void redactPanAndSecretHeaders() {
        assertTrue(ApiAudit.redactPan("card 4111111111111111 used").contains("[PAN]"));
        assertTrue(ApiAudit.isSecretHeader("X-Restlink-Api-Key"));
        assertTrue(ApiAudit.isSecretHeader("Authorization"));
        assertFalse(ApiAudit.isSecretHeader("Content-Type"));
    }

    @Test
    void tenantFromPath() {
        assertEquals("et", ApiAudit.tenantFromPath("/customers/et/cust-1"));
        assertEquals("et", ApiAudit.tenantFromPath("wallet/et/ACC-1/balance"));
        assertEquals("", ApiAudit.tenantFromPath("/wallet/cash-in"));
        assertEquals("", ApiAudit.tenantFromPath("/admin/status"));
    }

    @Test
    void clientIpPrefersForwardedFor() {
        assertEquals("10.1.2.3", ApiAudit.clientIp("10.1.2.3, 10.0.0.1", "127.0.0.1"));
        assertEquals("127.0.0.1", ApiAudit.clientIp(null, "127.0.0.1"));
    }
}
