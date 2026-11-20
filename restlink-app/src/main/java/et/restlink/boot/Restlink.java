package et.restlink.boot;

import et.restlink.core.CoreBankingService;

/**
 * Tiny service locator so SLEE-managed SBBs (not CDI beans) can reach the CDI-built
 * {@link CoreBankingService}. Set once by {@link RestlinkBootstrap} at startup.
 */
public final class Restlink {

    private static volatile CoreBankingService core;

    private Restlink() {}

    static void set(CoreBankingService service) { core = service; }

    public static CoreBankingService core() {
        CoreBankingService c = core;
        if (c == null) throw new IllegalStateException("CoreBankingService not bootstrapped yet");
        return c;
    }
}
