package et.restlink.spi.model;

/** Tenant-scoped customer identity. {@code externalId} is the core engine's id (may be null pre-create). */
public record CustomerRef(String tenantId, String externalId, String mobileNo) {
    public CustomerRef {
        tenantId = tenantId == null ? "" : tenantId.trim();
    }
}
