package et.restlink.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAuthTest {

    @Test
    void denyUnsetDefaultEvenWhenHeaderMatches() {
        assertEquals("api key not configured",
                ApiAuth.denyReason(ApiAuth.UNSET_DEFAULT, ApiAuth.UNSET_DEFAULT, "admin-secret", null));
        assertEquals("api key not configured",
                ApiAuth.denyReason(null, "anything", "admin-secret", null));
        assertEquals("api key not configured",
                ApiAuth.denyReason("  ", "  ", "admin-secret", null));
    }

    @Test
    void denyBadApiKey() {
        assertEquals("bad api key",
                ApiAuth.denyReason("wallet-secret", "wrong", "admin-secret", null));
        assertEquals("bad api key",
                ApiAuth.denyReason("wallet-secret", null, "admin-secret", null));
    }

    @Test
    void allowConfiguredApiKey() {
        assertNull(ApiAuth.denyReason("wallet-secret", "wallet-secret", "admin-secret", null));
    }

    @Test
    void adminKeyIsSuperuserEvenWithoutApiKey() {
        assertNull(ApiAuth.denyReason("wallet-secret", null, "admin-secret", "admin-secret"));
        assertNull(ApiAuth.denyReason(ApiAuth.UNSET_DEFAULT, null, "admin-secret", "admin-secret"));
    }

    @Test
    void coversPublicBankingPathsOnly() {
        assertTrue(ApiAuth.covers("/customers"));
        assertTrue(ApiAuth.covers("customers/et/cust-1"));
        assertTrue(ApiAuth.covers("/wallet/cash-in"));
        assertTrue(ApiAuth.covers("wallet/et/ACC-1/balance"));
        assertFalse(ApiAuth.covers("/admin/status"));
        assertFalse(ApiAuth.covers("admin"));
        assertFalse(ApiAuth.covers("/payments/pay/et/abc"));
        assertFalse(ApiAuth.covers(null));
    }
}
