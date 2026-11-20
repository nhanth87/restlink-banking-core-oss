package et.restlink.kyc;

import et.restlink.persist.WalletAccount;
import et.restlink.profile.CustomerDirectory;
import et.restlink.spi.payment.PaymentOperation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the account owner (wallet row) then applies {@link KycGate}.
 */
@ApplicationScoped
public class KycService {

    @Inject
    CustomerDirectory customers;

    public KycGate.Decision decide(PaymentOperation op, String tenantId, String accountId) {
        if (!KycGate.requiresApproved(op)) {
            return new KycGate.Allow();
        }
        Optional<String> customerId = ownerOf(tenantId, accountId);
        if (customerId.isEmpty()) {
            return new KycGate.Blocked("", KycGate.PENDING, "KYC not approved (unknown account owner)");
        }
        Optional<CustomerDirectory.CustomerHandle> h = customers.get(tenantId, customerId.get());
        String status = h.map(x -> x.profile().getKycStatus()).orElse(KycGate.PENDING);
        return KycGate.check(op, customerId.get(), status);
    }

    Optional<String> ownerOf(String tenantId, String accountId) {
        if (tenantId == null || accountId == null) {
            return Optional.empty();
        }
        WalletAccount a = WalletAccount.findById(accountId);
        if (a == null || !tenantId.equals(a.tenantId) || a.customerExternalId == null
                || a.customerExternalId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(a.customerExternalId);
    }
}
