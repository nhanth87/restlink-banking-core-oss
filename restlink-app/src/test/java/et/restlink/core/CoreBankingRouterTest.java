package et.restlink.core;

import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;
import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.model.Money;
import et.restlink.spi.payment.MoneyStepResult;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreBankingRouterTest {

    @Test
    void tenantBindingSelectsFineractWithoutFallingBackToWallet() {
        CountingPort wallet = new CountingPort(CoreProvider.WALLET_LEDGER);
        CountingPort fineract = new CountingPort(CoreProvider.FINERACT);
        CoreBankingRouter router = new CoreBankingRouter(
                CoreProvider.WALLET_LEDGER,
                CoreBankingRouter.bindings(CoreProvider.WALLET_LEDGER, "et:FINERACT,ke:WALLET_LEDGER"));
        router.register(wallet);
        router.register(fineract);

        assertEquals(CoreProvider.FINERACT, router.providerFor("et"));
        assertSame(fineract, router.portFor("et"));
        assertSame(wallet, router.portFor("ke"));
        assertSame(wallet, router.portFor("unknown-tenant"));

        PostTransaction post = new PostTransaction("req", "et", "1", PostTransaction.Direction.CREDIT,
                new Money(new BigDecimal("1"), "ETB"), "cash-in", "step-1");
        router.portFor("et").executeMoney(post);
        assertEquals(1, fineract.posts.get());
        assertEquals(0, wallet.posts.get());
    }

    @Test
    void unregisteredProviderFailsClosed() {
        CoreBankingRouter router = new CoreBankingRouter(
                CoreProvider.FINERACT, CoreBankingRouter.bindings(CoreProvider.FINERACT, ""));
        router.register(new CountingPort(CoreProvider.WALLET_LEDGER));
        assertThrows(IllegalStateException.class, () -> router.portFor("et"));
    }

    static final class CountingPort implements CoreBankingPort {
        private final CoreProvider provider;
        final AtomicInteger posts = new AtomicInteger();

        CountingPort(CoreProvider provider) {
            this.provider = provider;
        }

        @Override public CoreProvider provider() { return provider; }
        @Override public void submit(CoreBankingCommand command) { }
        @Override public boolean coreLive() { return true; }

        @Override
        public MoneyStepResult executeMoney(CoreBankingCommand command) {
            posts.incrementAndGet();
            return MoneyStepResult.committed("TXN-X", "TXN-X");
        }
    }
}
