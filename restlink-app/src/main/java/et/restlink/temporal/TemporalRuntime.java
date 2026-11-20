package et.restlink.temporal;

import et.restlink.workflow.RestlinkTaskQueues;
import et.restlink.workflow.payment.PaymentWorkflowImpl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit Temporal client + worker lifecycle. Money readiness requires a reachable Temporal
 * frontend and an actively polling worker — never "worker started" alone.
 */
@ApplicationScoped
public class TemporalRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(TemporalRuntime.class);

    @ConfigProperty(name = "restlink.temporal.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "restlink.temporal.target", defaultValue = "127.0.0.1:7233")
    String target;
    @ConfigProperty(name = "restlink.temporal.namespace", defaultValue = "restlink")
    String namespace;
    @ConfigProperty(name = "restlink.temporal.fail-open-on-startup", defaultValue = "false")
    boolean failOpenOnStartup;

    @Inject
    PaymentActivitiesImpl activities;

    private volatile WorkflowServiceStubs stubs;
    private volatile WorkflowClient client;
    private volatile WorkerFactory factory;
    private final AtomicBoolean workerPolling = new AtomicBoolean(false);
    private volatile String lastError;

    private static final long PROBE_TTL_MS = 1_000L;
    private volatile long lastProbeMs;
    private volatile boolean lastProbeLive;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.warn("[temporal] disabled — money APIs will return 503");
            lastError = "disabled";
            return;
        }
        try {
            start();
        } catch (RuntimeException e) {
            lastError = e.toString();
            workerPolling.set(false);
            if (failOpenOnStartup) {
                LOG.error("[temporal] startup failed (fail-open): {}", e.toString());
            } else {
                throw e;
            }
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        stop();
    }

    public synchronized void start() {
        if (client != null) return;
        WorkflowServiceStubsOptions stubOpts = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target)
                .setRpcTimeout(Duration.ofSeconds(10))
                .build();
        stubs = WorkflowServiceStubs.newServiceStubs(stubOpts);
        client = WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .setDataConverter(lenientConverter())
                .build());
        factory = WorkerFactory.newInstance(client);

        // Single worker on the workflow queue also hosts the activities (the workflow's ActivityOptions
        // set no separate queue, so activities inherit PAYMENT_WORKFLOW). A second activity-only worker
        // would poll a queue nothing schedules to.
        Worker wfWorker = factory.newWorker(RestlinkTaskQueues.PAYMENT_WORKFLOW);
        wfWorker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        wfWorker.registerActivitiesImplementations(activities);

        factory.start();
        workerPolling.set(true);
        lastError = null;
        LOG.info("[temporal] worker polling target={} ns={} wfQueue={}",
                target, namespace, RestlinkTaskQueues.PAYMENT_WORKFLOW);
    }

    /**
     * Lenient JSON converter: tolerate unknown fields so an old worker replaying a newer history
     * (added record field) does not wedge in-flight money workflows during a rolling N–N upgrade.
     */
    private static DataConverter lenientConverter() {
        ObjectMapper mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        return DefaultDataConverter.newDefaultInstance()
                .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(mapper));
    }

    public synchronized void stop() {
        workerPolling.set(false);
        if (factory != null) {
            // Drain in-flight money activities before tearing down the datasource.
            try { factory.shutdown(); factory.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ignore) { }
            factory = null;
        }
        if (stubs != null) {
            try { stubs.shutdown(); } catch (Exception ignore) { }
            stubs = null;
        }
        client = null;
    }

    public Optional<WorkflowClient> client() {
        return Optional.ofNullable(client);
    }

    public boolean temporalLive() {
        if (!enabled) return false;
        WorkflowServiceStubs s = stubs;
        if (s == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastProbeMs < PROBE_TTL_MS) {
            return lastProbeLive; // short-TTL cache: avoid a blocking gRPC probe on every hot-path call
        }
        boolean live;
        try {
            s.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
            live = true;
        } catch (Exception e) {
            lastError = e.toString();
            live = false;
        }
        lastProbeLive = live;
        lastProbeMs = now;
        return live;
    }

    public boolean workerPolling() {
        return workerPolling.get() && client != null;
    }

    public boolean moneyReady() {
        return temporalLive() && workerPolling();
    }

    public String target() { return target; }
    public String namespace() { return namespace; }
    public String lastError() { return lastError; }
}
