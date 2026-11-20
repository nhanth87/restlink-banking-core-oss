package et.restlink.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthTest {

    @Test
    void constantTimeEqualsMatches() {
        assertTrue(AdminAuth.constantTimeEquals("secret", "secret"));
        assertFalse(AdminAuth.constantTimeEquals("secret", "Secret"));
        assertFalse(AdminAuth.constantTimeEquals("secret", "secre"));
        assertFalse(AdminAuth.constantTimeEquals(null, "x"));
        assertFalse(AdminAuth.constantTimeEquals("x", null));
    }

    @Test
    void denyUnsetDefault() {
        assertEquals("admin key not configured",
                AdminAuth.denyReason(AdminAuth.UNSET_DEFAULT, AdminAuth.UNSET_DEFAULT));
        assertEquals("admin key not configured", AdminAuth.denyReason(null, "anything"));
    }

    @Test
    void denyBadKey() {
        assertEquals("bad admin key", AdminAuth.denyReason("real-secret-ok", "wrong"));
        assertEquals("bad admin key", AdminAuth.denyReason("real-secret-ok", null));
    }

    @Test
    void allowConfiguredKey() {
        assertNull(AdminAuth.denyReason("real-secret-ok", "real-secret-ok"));
    }

    @Test
    void rejectShortConfiguredKey() {
        assertEquals("admin key not configured", AdminAuth.denyReason("short", "short"));
        assertFalse(AdminAuth.isConfigured("short"));
        assertTrue(AdminAuth.isConfigured("real-secret-ok"));
    }
}
