package et.restlink.spi.payment;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable capability set advertised by a core provider. */
public record ProviderCapabilities(Set<ProviderCapability> caps) {

    public ProviderCapabilities {
        // EnumSet.copyOf(empty) throws — Jackson may deserialize {"caps":[]} as an empty List.
        // Unknown enum values may arrive as null after READ_UNKNOWN_ENUM_VALUES_AS_NULL (rolling upgrade).
        EnumSet<ProviderCapability> set = EnumSet.noneOf(ProviderCapability.class);
        if (caps != null) {
            for (ProviderCapability c : caps) {
                if (c != null) {
                    set.add(c);
                }
            }
        }
        caps = set;
    }

    public static ProviderCapabilities of(ProviderCapability... values) {
        EnumSet<ProviderCapability> set = EnumSet.noneOf(ProviderCapability.class);
        if (values != null) {
            for (ProviderCapability c : values) {
                if (c != null) set.add(c);
            }
        }
        return new ProviderCapabilities(set);
    }

    public boolean supports(ProviderCapability c) {
        return caps.contains(Objects.requireNonNull(c));
    }

    /** Safe for same-provider transfer without distributed Saga legs. */
    public boolean supportsNativeTransfer() {
        return supports(ProviderCapability.NATIVE_ATOMIC_TRANSFER)
                && supports(ProviderCapability.LOOKUP_BY_IDEMPOTENCY);
    }

    /**
     * Safe for cash-in / cash-out. Native atomic transfer implies a local money engine;
     * otherwise {@link ProviderCapability#POST_LEG} + lookup (Fineract deposit/withdrawal).
     * Transfer still requires {@link #supportsNativeTransfer()} or {@link #supportsDistributedSaga()}.
     */
    public boolean supportsSingleLeg() {
        return supportsNativeTransfer()
                || (supports(ProviderCapability.POST_LEG)
                && supports(ProviderCapability.LOOKUP_BY_IDEMPOTENCY));
    }

    /** Safe for cross-account Saga (reserve → credit → capture / release). */
    public boolean supportsDistributedSaga() {
        return supports(ProviderCapability.RESERVE)
                && supports(ProviderCapability.CAPTURE)
                && supports(ProviderCapability.RELEASE)
                && supports(ProviderCapability.REVERSE)
                && supports(ProviderCapability.LOOKUP_BY_IDEMPOTENCY);
    }
}
