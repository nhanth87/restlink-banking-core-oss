package et.restlink.spi.model;

/** Tenant-scoped account identity in the core engine. */
public record AccountRef(String tenantId, String accountId, String product, String currency) {
    public AccountRef {
        tenantId = tenantId == null ? "" : tenantId.trim();
    }
}
