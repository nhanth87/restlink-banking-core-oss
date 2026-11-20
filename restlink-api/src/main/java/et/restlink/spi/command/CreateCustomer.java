package et.restlink.spi.command;

/** Create/onboard a customer (CIF) in the tenant's core engine. */
public record CreateCustomer(
        String requestId,
        String tenantId,
        String firstName,
        String lastName,
        String mobileNo,
        String externalKycId
) implements CoreBankingCommand {
    public CreateCustomer {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
    }
}
