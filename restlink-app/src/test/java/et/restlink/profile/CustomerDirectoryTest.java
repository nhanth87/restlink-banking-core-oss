package et.restlink.profile;

import com.microjainslee.api.DurableProfileStore;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileMutation;
import com.microjainslee.core.InMemoryCmpFieldStore;

import et.restlink.profile.CustomerDirectory.CustomerHandle;
import et.restlink.profile.CustomerDirectory.CustomerSpec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerDirectoryTest {

    @Test
    void eachCustomerGetsOwnProfileAndCmp() {
        CustomerDirectory dir = directory();
        CustomerSpec specA = new CustomerSpec("Ada", "Lovelace", "0911000001", "kyc-a", "WALLET_LEDGER", "ETB", "ACTIVE");
        CustomerSpec specB = new CustomerSpec("Alan", "Turing", "0911000002", "kyc-b", "WALLET_LEDGER", "ETB", "ACTIVE");

        CustomerHandle a = dir.ensure("t1", "cust-a", specA);
        CustomerHandle b = dir.ensure("t1", "cust-b", specB);

        assertEquals("t1:cust-a", a.key());
        assertEquals("t1:cust-b", b.key());
        assertNotEquals(a.profile().getCustomerId(), b.profile().getCustomerId());
        assertNotEquals(a.cmp().entityId(), b.cmp().entityId());
        assertEquals("Ada", a.profile().getFirstName());
        assertEquals("Alan", b.profile().getFirstName());
        assertEquals(2, dir.profileCount());
    }

    @Test
    void ensureIsIdempotentAndDoesNotShareCmpState() {
        CustomerDirectory dir = directory();
        CustomerHandle first = dir.ensure("t1", "cust-a",
                new CustomerSpec("Ada", null, null, null, null, "ETB", "ACTIVE"));
        first.cmp().recordRequest("REQ-1");

        CustomerHandle again = dir.ensure("t1", "cust-a",
                new CustomerSpec("Ada", null, null, null, null, "ETB", "ACTIVE"));
        assertEquals("REQ-1", again.cmp().lastRequestId());
        assertEquals(1, dir.profileCount());

        CustomerHandle other = dir.ensure("t1", "cust-b",
                new CustomerSpec("Bob", null, null, null, null, "ETB", "ACTIVE"));
        other.cmp().recordRequest("REQ-2");
        assertEquals("REQ-1", again.cmp().lastRequestId());
        assertEquals("REQ-2", other.cmp().lastRequestId());
        assertNotSame(again.cmp().entityId(), other.cmp().entityId());
        assertTrue(dir.get("t1", "cust-a").isPresent());
    }

    @Test
    void durableHasNoTtlAndSurvivesDirectoryRestart() {
        MemStore durable = new MemStore();
        CustomerDirectory first = new CustomerDirectory(durable, new InMemoryCmpFieldStore());
        first.ensure("t1", "cust-a",
                new CustomerSpec("Ada", "Lovelace", "0911", "kyc-a", "WALLET_LEDGER", "ETB", "ACTIVE"));
        first.close();

        assertTrue(durable.rows.containsKey("Customer/t1:cust-a"));
        assertEquals("Ada", durable.rows.get("Customer/t1:cust-a").get("firstName"));

        CustomerDirectory restarted = new CustomerDirectory(durable, new InMemoryCmpFieldStore());
        assertTrue(restarted.durable());
        assertEquals(0, restarted.cacheSize());
        assertEquals(1, restarted.profileCount());
        assertEquals("Ada", restarted.get("t1", "cust-a").orElseThrow().profile().getFirstName());
        assertEquals(1, restarted.cacheSize());
        restarted.close();
    }

    @Test
    void lruEvictsWorkingSetButNotDurableRows() {
        MemStore durable = new MemStore();
        CustomerDirectory dir = new CustomerDirectory(durable, new InMemoryCmpFieldStore(), 2);
        dir.ensure("t1", "a", spec("A"));
        dir.ensure("t1", "b", spec("B"));
        dir.ensure("t1", "c", spec("C"));

        assertEquals(3, dir.profileCount());
        assertEquals(2, dir.cacheSize());
        assertEquals(2, dir.cacheMax());
        assertEquals("A", dir.get("t1", "a").orElseThrow().profile().getFirstName());
        assertEquals(3, durable.rows.size());
    }

    @Test
    void kycDefaultsPendingAndSetKycUpdatesDurableRow() {
        MemStore durable = new MemStore();
        CustomerDirectory dir = new CustomerDirectory(durable, new InMemoryCmpFieldStore());
        CustomerHandle h = dir.ensure("t1", "cust-a", spec("Ada"));
        assertEquals("PENDING", h.profile().getKycStatus());

        assertTrue(dir.setKyc("t1", "missing", "APPROVED", null, null).isEmpty());
        CustomerHandle approved = dir.setKyc("t1", "cust-a", "APPROVED", "NATIONAL_ID", "doc-1").orElseThrow();
        assertEquals("APPROVED", approved.profile().getKycStatus());
        assertEquals("NATIONAL_ID", approved.profile().getKycDocumentType());
        assertEquals("doc-1", approved.profile().getKycDocumentRef());

        CustomerDirectory restarted = new CustomerDirectory(durable, new InMemoryCmpFieldStore());
        assertEquals("APPROVED", restarted.get("t1", "cust-a").orElseThrow().profile().getKycStatus());
    }

    private static CustomerDirectory directory() {
        return new CustomerDirectory(new MemStore(), new InMemoryCmpFieldStore());
    }

    private static CustomerSpec spec(String first) {
        return new CustomerSpec(first, null, null, null, null, "ETB", "ACTIVE");
    }

    /** In-memory DurableProfileStore stand-in for Postgres (same contract). */
    private static final class MemStore implements DurableProfileStore {
        final Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();

        private static String key(ProfileID id) {
            return id.getProfileTableName() + "/" + id.getProfileName();
        }

        @Override
        public Map<String, Object> loadFields(ProfileID id) {
            Map<String, Object> v = rows.get(key(id));
            return v == null ? null : new LinkedHashMap<>(v);
        }

        @Override
        public void storeFields(ProfileID id, Map<String, Object> fields) {
            rows.put(key(id), new LinkedHashMap<>(fields));
        }

        @Override
        public void remove(ProfileID id) {
            rows.remove(key(id));
        }

        @Override
        public void storeBatch(List<ProfileMutation> mutations) {
            for (ProfileMutation m : mutations) {
                if (m.getType() == ProfileMutation.Type.DELETE) remove(m.getId());
                else storeFields(m.getId(), m.getFields());
            }
        }

        @Override
        public Map<String, Map<String, Object>> loadTable(String tableName) {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            String prefix = tableName + "/";
            rows.forEach((k, v) -> {
                if (k.startsWith(prefix)) out.put(k.substring(prefix.length()), new LinkedHashMap<>(v));
            });
            return out;
        }
    }
}
