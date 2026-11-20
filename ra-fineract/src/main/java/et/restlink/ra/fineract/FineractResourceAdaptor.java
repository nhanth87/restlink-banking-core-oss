package et.restlink.ra.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import et.restlink.ra.fineract.client.FineractRestClient;
import et.restlink.ra.fineract.client.FineractRestClient.HttpResult;
import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.command.CreateCustomer;
import et.restlink.spi.command.OpenAccount;
import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.command.QueryBalance;
import et.restlink.spi.command.ReverseTransaction;
import et.restlink.spi.command.TransferFunds;
import et.restlink.spi.events.AccountOpenedEvent;
import et.restlink.spi.events.BalanceEvent;
import et.restlink.spi.events.CoreErrorEvent;
import et.restlink.spi.events.CoreHeartbeatEvent;
import et.restlink.spi.events.CustomerCreatedEvent;
import et.restlink.spi.events.TransactionPostedEvent;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.spi.payment.MoneyOutcomeKind;
import et.restlink.spi.payment.MoneyStepResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Apache Fineract Resource Adaptor (Phase 2 core-banking provider): {@code raActive}/{@code raInactive},
 * a single link activity for heartbeats/responses, and {@code sendOutbound(CoreBankingCommand)} dispatch.
 *
 * <p>Command handlers implement Fineract community best practice:
 * <ul>
 *   <li>savings lifecycle for onboarding (create → approve → activate);</li>
 *   <li>deposit/withdrawal via {@code /savingsaccounts/{id}/transactions?command=...};</li>
 *   <li>every write body carries {@code dateFormat} + {@code locale} (else Fineract 400);</li>
 *   <li>money commands carry an {@code Idempotency-Key} derived from the caller's key + an
 *       {@code externalId} for traceability;</li>
 *   <li>link-truth: {@code coreLive} flips to {@code false} only on transport/5xx, never on a 4xx
 *       business validation error.</li>
 * </ul>
 */
public final class FineractResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(FineractResourceAdaptor.class);
    public static final String LINK_ACTIVITY_ID = "fineract-link";
    private static final String PROVIDER = "FINERACT";
    private static final ObjectMapper JSON = new ObjectMapper();

    private FineractRaConfig config = new FineractRaConfig();
    private RaBootstrapPort bootstrapPort;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile boolean live;
    private volatile ActivityHandle linkActivity;
    private volatile FineractRestClient client;

    public void setConfig(FineractRaConfig config) {
        this.config = config != null ? config : new FineractRaConfig();
    }

    public void setBootstrapPort(RaBootstrapPort bootstrapPort) {
        this.bootstrapPort = bootstrapPort;
    }

    public boolean live() {
        return live;
    }

    public void raActive() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        this.client = new FineractRestClient(config);
        ensureLinkActivity();
        // Peer truth: only live after a real probe, not on RA start.
        this.live = client.probe();
        LOG.info("[ra-fineract] ACTIVE base={} tenant={} live={}",
                config.baseUrl(), config.tenant(), live);
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        FineractRestClient c = client;
        client = null;
        if (c != null) {
            c.close();
        }
        endLinkActivity();
        live = false;
        LOG.info("[ra-fineract] INACTIVE");
    }

    public void sendOutbound(CoreBankingCommand cmd) {
        if (cmd == null || !active.get()) {
            return;
        }
        FineractRestClient c = client;
        if (c == null) {
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    cmd.getClass().getSimpleName(), "ra-fineract inactive"));
            return;
        }
        try {
            switch (cmd) {
                case CreateCustomer x -> handleCreateCustomer(c, x);
                case OpenAccount x -> handleOpenAccount(c, x);
                case QueryBalance x -> handleQueryBalance(c, x);
                case PostTransaction x -> fireMoney(x, executePost(c, x));
                case TransferFunds x -> fire(new CoreErrorEvent(x.requestId(), PROVIDER, x.tenantId(),
                        "TransferFunds", "use Temporal+NATIVE_ATOMIC_TRANSFER or proven accounttransfers"));
                case ReverseTransaction x -> fireMoney(x, executeReverse(c, x));
            }
        } catch (RuntimeException ex) {
            // Unexpected client-side failure — treat as transport for safety (peer may be unreachable).
            live = false;
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    cmd.getClass().getSimpleName(), ex.toString()));
        }
    }

    /**
     * Temporal money fence. Recovers {@code coreLive} with a probe if the circuit is open; never
     * posts while the peer is down. Transfer is rejected (no NATIVE_ATOMIC_TRANSFER).
     */
    public MoneyStepResult executeMoney(CoreBankingCommand cmd) {
        if (cmd == null) {
            return MoneyStepResult.businessReject("null command");
        }
        if (!active.get()) {
            return MoneyStepResult.transientFailure("ra-fineract inactive");
        }
        FineractRestClient c = client;
        if (c == null) {
            return MoneyStepResult.transientFailure("ra-fineract inactive");
        }
        if (!recoverLive(c)) {
            return MoneyStepResult.transientFailure("fineract peer down");
        }
        try {
            MoneyStepResult r = switch (cmd) {
                case PostTransaction x -> executePost(c, x);
                case ReverseTransaction x -> executeReverse(c, x);
                case TransferFunds x -> MoneyStepResult.businessReject(
                        "TransferFunds not supported on Fineract (no NATIVE_ATOMIC_TRANSFER)");
                default -> MoneyStepResult.businessReject("not a money command: " + cmd.getClass().getSimpleName());
            };
            fireMoney(cmd, r);
            return r;
        } catch (RuntimeException ex) {
            live = false;
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    cmd.getClass().getSimpleName(), ex.toString()));
            return MoneyStepResult.transientFailure(ex.getMessage());
        }
    }

    public Optional<String> lookupByIdempotency(String tenantId, String accountId, String idempotencyKey) {
        FineractRestClient c = client;
        if (c == null || accountId == null || accountId.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        HttpResult r = c.get("/savingsaccounts/" + accountId + "?associations=transactions");
        if (r.isPeerDown()) {
            live = false;
            return Optional.empty();
        }
        live = true; // successful GET closes the money circuit
        if (!r.is2xx()) {
            return Optional.empty();
        }
        return FineractMoneyResults.findTxnIdByExternalId(r.body(), trim100(idempotencyKey));
    }

    /** Read-only savings balance. Never posts. */
    public BalanceQuery queryBalance(String tenantId, String accountId) {
        if (!active.get()) {
            return BalanceQuery.transientFailure("ra-fineract inactive");
        }
        FineractRestClient c = client;
        if (c == null) {
            return BalanceQuery.transientFailure("ra-fineract inactive");
        }
        if (accountId == null || accountId.isBlank()) {
            return BalanceQuery.notFound("accountId required");
        }
        HttpResult r = c.get("/savingsaccounts/" + accountId + "?associations=summary");
        if (r.isPeerDown()) {
            live = false;
        } else if (r.is2xx()) {
            live = true;
        }
        return FineractMoneyResults.ofBalance(r, tenantId, accountId);
    }

    /** Probe-on-demand so a prior 5xx does not stick the money circuit open. */
    private boolean recoverLive(FineractRestClient c) {
        if (live) {
            return true;
        }
        live = c.probe();
        return live;
    }

    private void fireMoney(CoreBankingCommand cmd, MoneyStepResult r) {
        if (r == null) {
            return;
        }
        if (r.kind() == MoneyOutcomeKind.COMMITTED || r.kind() == MoneyOutcomeKind.NO_OP) {
            String acct = switch (cmd) {
                case PostTransaction p -> p.accountId();
                case ReverseTransaction rev -> rev.accountId();
                default -> "";
            };
            fire(new TransactionPostedEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    acct, r.coreTxnId() == null ? "NOOP" : r.coreTxnId(), true,
                    r.message() == null ? r.kind().name() : r.message()));
        } else if (r.kind() == MoneyOutcomeKind.BUSINESS_REJECT) {
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    cmd.getClass().getSimpleName(), r.message()));
        }
        // TRANSIENT/AMBIGUOUS: do not complete pending as success; Temporal retries / reconciles.
    }

    // --- command handlers (real Fineract REST per best practice) ---

    private void handleCreateCustomer(FineractRestClient c, CreateCustomer cmd) {
        String extId = cmd.externalKycId() != null && !cmd.externalKycId().isBlank()
                ? cmd.externalKycId() : cmd.mobileNo();
        ObjectNode body = JSON.createObjectNode();
        body.put("officeId", config.defaultOfficeId());
        body.put("firstname", safe(cmd.firstName(), "Restlink"));
        body.put("lastname", safe(cmd.lastName(), "Customer"));
        body.put("active", true);
        body.put("activationDate", today());
        if (cmd.mobileNo() != null) body.put("mobileNo", cmd.mobileNo());
        if (extId != null) body.put("externalId", trim100(extId));
        stamp(body);

        String seed = cmd.tenantId() + ":createClient:" + extId;
        HttpResult r = c.postIdempotent("/clients", body.toString(), seed);
        if (r.is2xx()) {
            String clientId = num(r.body(), "clientId", "resourceId");
            fire(new CustomerCreatedEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    clientId, true, "client created"));
        } else {
            failByStatus(cmd.requestId(), cmd.tenantId(), "CreateCustomer", r);
        }
    }

    private void handleOpenAccount(FineractRestClient c, OpenAccount cmd) {
        Optional<Long> clientPk = resolveNumericClientId(c, cmd);
        if (clientPk.isEmpty()) {
            return;
        }
        // 1) submit application — clientId is Fineract's numeric PK, never the external/KYC string
        ObjectNode app = JSON.createObjectNode();
        app.put("clientId", clientPk.get());
        app.put("productId", config.savingsProductId());
        app.put("submittedOnDate", today());
        stamp(app);
        String seed = cmd.tenantId() + ":openSavings:" + cmd.customerExternalId();
        HttpResult r1 = c.postIdempotent("/savingsaccounts", app.toString(), seed);
        if (!r1.is2xx()) { failByStatus(cmd.requestId(), cmd.tenantId(), "OpenAccount", r1); return; }
        String savingsId = num(r1.body(), "savingsId", "resourceId");

        // 2) approve  3) activate  (lifecycle — Fineract savings starts as an application)
        ObjectNode approve = JSON.createObjectNode();
        approve.put("approvedOnDate", today());
        stamp(approve);
        HttpResult r2 = c.post("/savingsaccounts/" + savingsId + "?command=approve", approve.toString());
        if (r2.isPeerDown()) { failByStatus(cmd.requestId(), cmd.tenantId(), "OpenAccount.approve", r2); return; }

        ObjectNode activate = JSON.createObjectNode();
        activate.put("activationDate", today());
        stamp(activate);
        HttpResult r3 = c.post("/savingsaccounts/" + savingsId + "?command=activate", activate.toString());
        if (r3.isPeerDown()) { failByStatus(cmd.requestId(), cmd.tenantId(), "OpenAccount.activate", r3); return; }

        fire(new AccountOpenedEvent(cmd.requestId(), PROVIDER, cmd.tenantId(), savingsId,
                safe(cmd.product(), "SAVINGS"), safe(cmd.currency(), "ETB"),
                r3.is2xx(), "savings opened"));
    }

    /**
     * Look up Fineract client by {@code externalId}, then use the numeric PK. Never put the
     * Restlink customer string into {@code clientId}.
     */
    Optional<Long> resolveNumericClientId(FineractRestClient c, OpenAccount cmd) {
        if (cmd.customerExternalId() == null || cmd.customerExternalId().isBlank()) {
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    "OpenAccount", "customerExternalId required"));
            return Optional.empty();
        }
        HttpResult lookup = c.get(FineractClientIds.lookupPath(cmd.customerExternalId()));
        if (lookup.isPeerDown()) {
            failByStatus(cmd.requestId(), cmd.tenantId(), "OpenAccount.clientLookup", lookup);
            return Optional.empty();
        }
        Optional<String> parsed = FineractClientIds.parseClientId(lookup.body());
        Optional<Long> numeric = parsed.flatMap(FineractClientIds::numericClientId);
        if (numeric.isEmpty()) {
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    "OpenAccount", "Fineract client not found for externalId"));
            return Optional.empty();
        }
        return numeric;
    }

    private MoneyStepResult executePost(FineractRestClient c, PostTransaction cmd) {
        String command = cmd.direction() == PostTransaction.Direction.CREDIT ? "deposit" : "withdrawal";
        ObjectNode body = JSON.createObjectNode();
        body.put("transactionDate", today());
        body.put("transactionAmount", cmd.amount().amount());
        if (cmd.idempotencyKey() != null) {
            body.put("externalId", trim100(cmd.idempotencyKey()));
        }
        stamp(body);

        String path = "/savingsaccounts/" + cmd.accountId() + "/transactions?command=" + command;
        // Same idempotencyKey on retry ⇒ Fineract returns the first response, no double post.
        HttpResult r = c.postIdempotent(path, body.toString(), cmd.idempotencyKey());
        if (r.isPeerDown()) {
            live = false;
        }
        String txnId = r.is2xx() ? num(r.body(), "resourceId", "savingsAccountTransactionId") : null;
        return FineractMoneyResults.ofWrite(r, txnId);
    }

    private void handleQueryBalance(FineractRestClient c, QueryBalance cmd) {
        BalanceQuery q = queryBalance(cmd.tenantId(), cmd.accountId());
        if (q.kind() == BalanceQuery.Kind.FOUND) {
            fire(new BalanceEvent(cmd.requestId(), PROVIDER, cmd.tenantId(), cmd.accountId(),
                    q.snapshot().available(), q.snapshot().ledger(), true, "balance"));
            return;
        }
        if (q.kind() == BalanceQuery.Kind.TRANSIENT) {
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    "QueryBalance", q.message()));
            return;
        }
        fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                "QueryBalance", q.message() == null ? "not found" : q.message()));
    }

    /**
     * Compensating reverse via Fineract undo of the original savings txn. Undo is direction-correct
     * for both cash-in (deposit) and cash-out (withdrawal). Blank original id → no-op (forward
     * never committed). Same reverse step key ⇒ same Idempotency-Key.
     */
    private MoneyStepResult executeReverse(FineractRestClient c, ReverseTransaction cmd) {
        if (cmd.originalCoreTxnId() == null || cmd.originalCoreTxnId().isBlank()) {
            return MoneyStepResult.noOp("forward never committed");
        }
        ObjectNode body = JSON.createObjectNode();
        stamp(body);
        body.put("transactionDate", today());
        String path = "/savingsaccounts/" + cmd.accountId()
                + "/transactions/" + cmd.originalCoreTxnId() + "?command=undo";
        HttpResult r = c.postIdempotent(path, body.toString(), cmd.idempotencyKey());
        if (r.isPeerDown()) {
            live = false;
        }
        String txnId = r.is2xx() ? num(r.body(), "resourceId", "savingsAccountTransactionId") : null;
        if (txnId == null || txnId.isBlank()) {
            txnId = cmd.originalCoreTxnId();
        }
        return FineractMoneyResults.ofUndo(r, txnId);
    }

    /** Link-truth: only a peer-down (transport/5xx) result flips {@code live}; a 4xx is a business error. */
    private void failByStatus(String requestId, String tenantId, String op, HttpResult r) {
        if (r.isPeerDown()) {
            live = false;
            String detail = r.transportError() ? r.transportDetail() : "HTTP " + r.status();
            LOG.warn("[ra-fineract] {} peer-down: {}", op, detail);
            fire(new CoreErrorEvent(requestId, PROVIDER, tenantId, op, "peer-down: " + detail));
        } else {
            LOG.info("[ra-fineract] {} business error HTTP {} body={}", op, r.status(), snippet(r.body()));
            fire(new CoreErrorEvent(requestId, PROVIDER, tenantId, op,
                    "HTTP " + r.status() + ": " + snippet(r.body())));
        }
    }

    public void heartbeat() {
        FineractRestClient c = client;
        if (c != null) {
            live = c.probe();
        }
        fire(new CoreHeartbeatEvent("-", PROVIDER, live, System.currentTimeMillis()));
    }

    // --- helpers ---

    private void stamp(ObjectNode body) {
        body.put("dateFormat", config.dateFormat());
        body.put("locale", config.locale());
    }

    private String today() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern(config.dateFormat(),
                Locale.forLanguageTag(config.locale())));
    }

    private static String num(String body, String... fields) {
        try {
            JsonNode n = JSON.readTree(body);
            for (String f : fields) {
                if (n.hasNonNull(f)) return n.get(f).asText();
            }
        } catch (Exception ignore) { }
        return "";
    }

    private static String safe(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }

    private static String trim100(String v) {
        return v.length() <= 100 ? v : v.substring(0, 100);
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String s = body.strip();
        return s.length() <= 240 ? s : s.substring(0, 240);
    }

    private void fire(SleeEvent event) {
        RaBootstrapPort bp = bootstrapPort;
        ActivityHandle h = linkActivity;
        if (bp != null && h != null) {
            bp.fireEvent(event, h, null);
        }
    }

    private void ensureLinkActivity() {
        RaBootstrapPort bp = bootstrapPort;
        if (bp == null) {
            return;
        }
        if (linkActivity == null) {
            synchronized (this) {
                if (linkActivity == null) {
                    linkActivity = bp.createActivityHandle(LINK_ACTIVITY_ID);
                }
            }
        }
    }

    private void endLinkActivity() {
        RaBootstrapPort bp = bootstrapPort;
        ActivityHandle h = linkActivity;
        linkActivity = null;
        if (bp != null && h != null) {
            bp.endActivity(h);
        }
    }
}
