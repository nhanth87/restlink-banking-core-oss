package et.restlink.core;

import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Picks the {@link CoreBankingPort} for a tenant. Per-tenant provider binding (WALLET_LEDGER / FINERACT
 * / TEMENOS) is a config choice — the swappable core-banking seam. Unregistered bindings fail closed
 * (no silent fallback onto the wallet).
 */
public final class CoreBankingRouter {

    private final Map<CoreProvider, CoreBankingPort> ports = new EnumMap<>(CoreProvider.class);
    private final Function<String, CoreProvider> tenantBinding;
    private final CoreProvider defaultProvider;

    public CoreBankingRouter(CoreProvider defaultProvider, Function<String, CoreProvider> tenantBinding) {
        this.defaultProvider = defaultProvider == null ? CoreProvider.WALLET_LEDGER : defaultProvider;
        this.tenantBinding = tenantBinding == null ? t -> this.defaultProvider : tenantBinding;
    }

    public void register(CoreBankingPort port) {
        ports.put(port.provider(), port);
    }

    public CoreProvider providerFor(String tenantId) {
        CoreProvider p = tenantBinding.apply(tenantId);
        return p == null ? defaultProvider : p;
    }

    public CoreBankingPort portFor(String tenantId) {
        CoreProvider p = providerFor(tenantId);
        CoreBankingPort port = ports.get(p);
        if (port == null) {
            throw new IllegalStateException("no CoreBankingPort registered for provider " + p
                    + " (tenant " + tenantId + ")");
        }
        return port;
    }

    public Optional<CoreBankingPort> port(CoreProvider provider) {
        return Optional.ofNullable(ports.get(provider));
    }

    public Map<CoreProvider, CoreBankingPort> ports() { return Map.copyOf(ports); }

    public CoreProvider defaultProvider() { return defaultProvider; }

    /**
     * Parse {@code et:FINERACT,ke:WALLET_LEDGER} into a tenant binding. Unknown tokens are ignored
     * (fail closed at {@link #portFor} if the resulting provider was never registered).
     */
    public static Function<String, CoreProvider> bindings(CoreProvider defaultProvider, String raw) {
        CoreProvider def = defaultProvider == null ? CoreProvider.WALLET_LEDGER : defaultProvider;
        Map<String, CoreProvider> map = new HashMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String[] kv = part.split("[:|=]", 2);
                if (kv.length != 2) {
                    continue;
                }
                try {
                    map.put(kv[0].trim(), CoreProvider.valueOf(kv[1].trim().toUpperCase()));
                } catch (IllegalArgumentException ignore) {
                    // unknown provider name — skip; tenant stays on default
                }
            }
        }
        return tenantId -> map.getOrDefault(tenantId, def);
    }
}
