package et.restlink.api;

import et.restlink.persist.ApiAuditEntry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Write-through API audit. Failures must not fail the banking call (caller logs and continues).
 */
@ApplicationScoped
public class ApiAuditStore {

    @Transactional
    public void record(ApiAuditEntry.New row) {
        if (row == null) {
            return;
        }
        ApiAuditEntry e = new ApiAuditEntry();
        e.occurredMs = System.currentTimeMillis();
        e.tenantId = blankToNull(row.tenantId());
        e.actorId = row.actorId() == null || row.actorId().isBlank() ? "anonymous" : row.actorId();
        e.method = row.method();
        e.path = trim(row.path(), 240);
        e.clientIp = trim(row.clientIp(), 64);
        e.status = row.status();
        e.persist();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static String trim(String v, int max) {
        if (v == null) {
            return "";
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
