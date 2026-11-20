package et.restlink.ra.fineract;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FineractClientIdsTest {

    @Test
    void lookupPathEncodesExternalId() {
        assertEquals("/clients?externalId=cust-001", FineractClientIds.lookupPath("cust-001"));
        assertEquals("/clients?externalId=a+b", FineractClientIds.lookupPath("a b"));
        assertThrows(IllegalArgumentException.class, () -> FineractClientIds.lookupPath("  "));
    }

    @Test
    void parsePageItemsNumericId() {
        String body = "{\"totalFilteredRecords\":1,\"pageItems\":[{\"id\":42,\"externalId\":\"cust-001\"}]}";
        assertEquals(Optional.of("42"), FineractClientIds.parseClientId(body));
        assertEquals(Optional.of(42L), FineractClientIds.numericClientId("42"));
    }

    @Test
    void parseSingleClientBody() {
        assertEquals(Optional.of("7"), FineractClientIds.parseClientId("{\"clientId\":7}"));
    }

    @Test
    void rejectBlankAndNonNumeric() {
        assertTrue(FineractClientIds.parseClientId("{}").isEmpty());
        assertTrue(FineractClientIds.parseClientId("not-json").isEmpty());
        assertTrue(FineractClientIds.numericClientId("cust-001").isEmpty());
        assertTrue(FineractClientIds.numericClientId("12a").isEmpty());
    }

    @Test
    void neverTreatExternalIdStringAsClientPk() {
        assertTrue(FineractClientIds.numericClientId("kyc-ada-lovelace").isEmpty(),
                "external KYC string must not be sent as Fineract clientId");
    }
}
