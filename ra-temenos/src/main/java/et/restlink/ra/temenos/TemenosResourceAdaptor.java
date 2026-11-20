package et.restlink.ra.temenos;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import et.restlink.ra.temenos.client.OfsClient;
import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.command.CreateCustomer;
import et.restlink.spi.command.OpenAccount;
import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.command.QueryBalance;
import et.restlink.spi.command.ReverseTransaction;
import et.restlink.spi.command.TransferFunds;
import et.restlink.spi.events.CoreErrorEvent;
import et.restlink.spi.events.CoreHeartbeatEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Temenos T24/Transact Resource Adaptor (Phase 2 provider) over OFS. Command handlers build OFS
 * messages and fire SPI events.
 */
public final class TemenosResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(TemenosResourceAdaptor.class);
    public static final String LINK_ACTIVITY_ID = "temenos-link";
    private static final String PROVIDER = "TEMENOS";

    private TemenosRaConfig config = new TemenosRaConfig();
    private RaBootstrapPort bootstrapPort;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile boolean live;
    private volatile ActivityHandle linkActivity;
    private volatile OfsClient client;

    public void setConfig(TemenosRaConfig config) {
        this.config = config != null ? config : new TemenosRaConfig();
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
        this.client = new OfsClient(config);
        ensureLinkActivity();
        this.live = client.probe();
        LOG.info("[ra-temenos] ACTIVE ofs={} company={} live={}",
                config.ofsUrl(), config.company(), live);
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        OfsClient c = client;
        client = null;
        if (c != null) {
            c.close();
        }
        endLinkActivity();
        live = false;
        LOG.info("[ra-temenos] INACTIVE");
    }

    public void sendOutbound(CoreBankingCommand cmd) {
        if (cmd == null || !active.get()) {
            return;
        }
        try {
            switch (cmd) {
                case CreateCustomer c -> notImplemented(c.requestId(), c.tenantId(), "CreateCustomer");
                case OpenAccount c -> notImplemented(c.requestId(), c.tenantId(), "OpenAccount");
                case PostTransaction c -> notImplemented(c.requestId(), c.tenantId(), "PostTransaction");
                case QueryBalance c -> notImplemented(c.requestId(), c.tenantId(), "QueryBalance");
                case TransferFunds c -> notImplemented(c.requestId(), c.tenantId(), "TransferFunds");
                case ReverseTransaction c -> notImplemented(c.requestId(), c.tenantId(), "ReverseTransaction");
            }
        } catch (RuntimeException ex) {
            live = false;
            fire(new CoreErrorEvent(cmd.requestId(), PROVIDER, cmd.tenantId(),
                    cmd.getClass().getSimpleName(), ex.getMessage()));
        }
    }

    // OFS message templates land here in Phase 2, e.g.
    //   CUSTOMER,RESTLINK/I/PROCESS//0,GIVEN.NAMES=..,FAMILY.NAME=..
    //   ACCOUNT,RESTLINK/I/PROCESS//0,CUSTOMER=..,CATEGORY=..,CURRENCY=..
    //   FUNDS.TRANSFER,RESTLINK/I/PROCESS//0,DEBIT.ACCT.NO=..,CREDIT.ACCT.NO=..,AMOUNT=..
    private void notImplemented(String requestId, String tenantId, String op) {
        fire(new CoreErrorEvent(requestId, PROVIDER, tenantId, op,
                "ra-temenos scaffold: " + op + " not implemented (Phase 2)"));
    }

    public void heartbeat() {
        OfsClient c = client;
        if (c != null) {
            live = c.probe();
        }
        fire(new CoreHeartbeatEvent("-", PROVIDER, live, System.currentTimeMillis()));
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
