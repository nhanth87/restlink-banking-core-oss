package et.restlink.spi.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCapabilitiesTest {

    @Test
    void walletNativeImpliesSingleLegButNotDistributed() {
        ProviderCapabilities caps = ProviderCapabilities.of(
                ProviderCapability.NATIVE_ATOMIC_TRANSFER,
                ProviderCapability.LOOKUP_BY_IDEMPOTENCY,
                ProviderCapability.REVERSE);
        assertTrue(caps.supportsNativeTransfer());
        assertTrue(caps.supportsSingleLeg());
        assertFalse(caps.supportsDistributedSaga());
    }

    @Test
    void fineractPostLegAllowsCashInOutNotTransfer() {
        ProviderCapabilities caps = ProviderCapabilities.of(
                ProviderCapability.POST_LEG,
                ProviderCapability.LOOKUP_BY_IDEMPOTENCY,
                ProviderCapability.REVERSE);
        assertTrue(caps.supportsSingleLeg());
        assertFalse(caps.supportsNativeTransfer());
        assertFalse(caps.supportsDistributedSaga());
    }

    @Test
    void postLegWithoutLookupFailsClosed() {
        ProviderCapabilities caps = ProviderCapabilities.of(ProviderCapability.POST_LEG);
        assertFalse(caps.supportsSingleLeg());
    }

    @Test
    void emptyCapsFailClosed() {
        ProviderCapabilities caps = ProviderCapabilities.of();
        assertFalse(caps.supportsSingleLeg());
        assertFalse(caps.supportsNativeTransfer());
        assertFalse(caps.supportsDistributedSaga());
    }
}
