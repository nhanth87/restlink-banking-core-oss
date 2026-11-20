package et.restlink.boot;

import et.restlink.api.AdminAuth;
import et.restlink.cluster.RestlinkClusterViewBridge;
import et.restlink.cluster.RestlinkInfinispanManager;
import et.restlink.cluster.ClusterMaps;
import et.restlink.cluster.ClusterModePolicy;
import et.restlink.cluster.LocalClusterMaps;
import et.restlink.core.CoreBankingRouter;
import et.restlink.core.CoreBankingService;
import et.restlink.core.PendingRequest;
import et.restlink.payment.PaymentSagaRepository;
import et.restlink.persist.PgCmpFieldStore;
import et.restlink.persist.PgDurableProfileStore;
import et.restlink.persist.TxnJournal;
import et.restlink.profile.CustomerDirectory;
import et.restlink.ra.fineract.FineractRaConfig;
import et.restlink.ra.fineract.FineractRaEndpoint;
import et.restlink.ra.temenos.TemenosRaConfig;
import et.restlink.ra.temenos.TemenosRaEndpoint;
import et.restlink.spi.CoreProvider;
import et.restlink.wallet.PgWalletLedgerRepository;
import et.restlink.wallet.WalletLedgerProvider;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the N–N banking graph. Money orchestration is Temporal (separate {@code TemporalRuntime});
 * this bootstrap wires providers, profiles, and non-money correlation.
 */
@ApplicationScoped
public class RestlinkBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(RestlinkBootstrap.class);

    @ConfigProperty(name = "restlink.cluster.mode", defaultValue = "LOCAL")
    String clusterMode;
    @ConfigProperty(name = "restlink.cluster.name", defaultValue = "restlink")
    String clusterName;
    @ConfigProperty(name = "restlink.node.name", defaultValue = "restlink-1")
    String nodeName;
    @ConfigProperty(name = "restlink.core.default-provider", defaultValue = "WALLET_LEDGER")
    String defaultProvider;
    @ConfigProperty(name = "restlink.core.tenant-bindings", defaultValue = "")
    String tenantBindings;
    @ConfigProperty(name = "restlink.profile.cache-max", defaultValue = "10000")
    int profileCacheMax;

    @ConfigProperty(name = "restlink.fineract.base-url", defaultValue = "")
    String fineractBaseUrl;
    @ConfigProperty(name = "restlink.fineract.tenant", defaultValue = "default")
    String fineractTenant;
    @ConfigProperty(name = "restlink.fineract.user", defaultValue = "mifos")
    String fineractUser;
    @ConfigProperty(name = "restlink.fineract.password", defaultValue = "password")
    String fineractPassword;
    @ConfigProperty(name = "restlink.fineract.insecure-tls", defaultValue = "false")
    boolean fineractInsecureTls;
    @ConfigProperty(name = "restlink.fineract.allow-insecure-tls-lab", defaultValue = "false")
    boolean fineractAllowInsecureTlsLab;
    @ConfigProperty(name = "restlink.admin.key", defaultValue = "change-me")
    String adminKey;

    @ConfigProperty(name = "restlink.temenos.ofs-url", defaultValue = "")
    String temenosOfsUrl;
    @ConfigProperty(name = "restlink.temenos.user", defaultValue = "INPUTT")
    String temenosUser;
    @ConfigProperty(name = "restlink.temenos.password", defaultValue = "123456")
    String temenosPassword;
    @ConfigProperty(name = "restlink.temenos.company", defaultValue = "GB0010001")
    String temenosCompany;

    @Inject
    PgWalletLedgerRepository walletRepo;
    @Inject
    AgroalDataSource dataSource;
    @Inject
    PaymentSagaRepository paymentSagas;

    private volatile ClusterMaps clusterMaps;
    private volatile CoreBankingRouter router;
    private volatile CoreBankingService service;
    private volatile RestlinkClusterViewBridge viewBridge;
    private volatile CustomerDirectory customers;
    private volatile WalletLedgerProvider walletProvider;

    private synchronized void init() {
        if (service != null) return;

        clusterMaps = ClusterModePolicy.isDistSync(clusterMode)
                ? new RestlinkInfinispanManager(clusterName, nodeName)
                : new LocalClusterMaps(nodeName);
        String localWarn = ClusterModePolicy.localWarning(clusterMode);
        if (localWarn != null) {
            LOG.warn("[restlink-boot] {}", localWarn);
        }
        if (!AdminAuth.isConfigured(adminKey)) {
            LOG.error("[restlink-boot] RESTLINK_ADMIN_KEY is unset/short — admin endpoints reject until configured");
        }

        CoreProvider def = CoreProvider.valueOf(defaultProvider.toUpperCase());
        router = new CoreBankingRouter(def, CoreBankingRouter.bindings(def, tenantBindings));

        walletProvider = new WalletLedgerProvider(walletRepo);
        router.register(walletProvider);

        service = new CoreBankingService(clusterMaps, router);
        walletProvider.setEventSink(service::onEvent);
        service.setJournalSink(this::journal);
        service.setEventInbox(paymentSagas);

        registerFineractIfConfigured();
        registerTemenosIfConfigured();
        if (router.port(def).isEmpty() && def != CoreProvider.WALLET_LEDGER) {
            LOG.error("[restlink-boot] default provider {} is not registered — money for that binding fail-closed (no wallet fallback)",
                    def);
        }

        viewBridge = new RestlinkClusterViewBridge(clusterMaps, service.pending(), router);
        viewBridge.start();

        PgDurableProfileStore pgProfiles = new PgDurableProfileStore(dataSource);
        PgCmpFieldStore pgCmp = new PgCmpFieldStore(dataSource, profileCacheMax);
        customers = new CustomerDirectory(pgProfiles, pgCmp, profileCacheMax);
        Restlink.set(service);
        LOG.info("[restlink-boot] core ready mode={} node={} defaultProvider={} profiles={} cacheMax={} money=Temporal",
                clusterMaps.mode(), clusterMaps.localNode(), def, customers.profileCount(), profileCacheMax);
    }

    private void registerFineractIfConfigured() {
        if (fineractBaseUrl == null || fineractBaseUrl.isBlank()) {
            return;
        }
        FineractRaConfig cfg = new FineractRaConfig()
                .baseUrl(fineractBaseUrl)
                .tenant(fineractTenant)
                .user(fineractUser)
                .password(fineractPassword)
                .insecureTls(fineractInsecureTls)
                .allowInsecureTlsLab(fineractAllowInsecureTlsLab);
        FineractRaEndpoint fineract = new FineractRaEndpoint();
        fineract.setConfig(cfg);
        fineract.activate(new RaEventBridge(service));
        router.register(fineract);
        LOG.info("[restlink-boot] FINERACT registered base={} tenant={} live={} caps={}",
                fineractBaseUrl, fineractTenant, fineract.coreLive(), fineract.capabilities().caps());
    }

    private void registerTemenosIfConfigured() {
        if (temenosOfsUrl == null || temenosOfsUrl.isBlank()) {
            return;
        }
        TemenosRaConfig cfg = new TemenosRaConfig()
                .ofsUrl(temenosOfsUrl)
                .user(temenosUser)
                .password(temenosPassword)
                .company(temenosCompany);
        TemenosRaEndpoint temenos = new TemenosRaEndpoint();
        temenos.setConfig(cfg);
        temenos.activate(new RaEventBridge(service));
        router.register(temenos);
        LOG.info("[restlink-boot] TEMENOS registered ofs={} company={} live={} caps={} (money fail-closed until OFS ops land)",
                temenosOfsUrl, temenosCompany, temenos.coreLive(), temenos.capabilities().caps());
    }

    void onStart(@Observes StartupEvent ev) {
        init();
    }

    @Produces
    @ApplicationScoped
    CoreBankingService coreBankingService() {
        init();
        return service;
    }

    @Produces
    @ApplicationScoped
    ClusterMaps clusterMaps() {
        init();
        return clusterMaps;
    }

    @Produces
    @ApplicationScoped
    CoreBankingRouter coreBankingRouter() {
        init();
        return router;
    }

    @Produces
    @ApplicationScoped
    CustomerDirectory customerDirectory() {
        init();
        return customers;
    }

    @Produces
    @ApplicationScoped
    WalletLedgerProvider walletLedgerProvider() {
        init();
        return walletProvider;
    }

    @Transactional
    void journal(PendingRequest pr) {
        try {
            if (TxnJournal.findById(pr.requestId()) != null) return;
        } catch (RuntimeException e) {
            LOG.debug("journal snapshot skipped req={}: {}", pr.requestId(), e.toString());
        }
    }

    @PreDestroy
    void stop() {
        if (customers != null) customers.close();
        if (clusterMaps instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignore) { }
        }
    }
}
