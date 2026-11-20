package et.restlink.api;

import et.restlink.admin.AdminPageRenderer;
import et.restlink.cluster.ClusterMaps;
import et.restlink.core.CoreBankingService;
import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;
import et.restlink.core.CoreBankingRouter;
import et.restlink.payment.PaymentSagaRepository;
import et.restlink.profile.CustomerDirectory;
import et.restlink.spi.payment.PaymentState;
import et.restlink.temporal.TemporalRuntime;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin status — peer truth only. Temporal money readiness = frontend reachable AND workers polling.
 * HTML at {@code GET /admin} (disk templates + HTMX partials). JSON at {@code GET /admin/status}.
 */
@Path("/admin")
public class AdminResource {

    @Inject
    ClusterMaps cluster;
    @Inject
    CoreBankingService core;
    @Inject
    CoreBankingRouter router;
    @Inject
    CustomerDirectory customers;
    @Inject
    TemporalRuntime temporal;
    @Inject
    PaymentSagaRepository sagas;
    @Inject
    AdminPageRenderer pages;

    @ConfigProperty(name = "restlink.admin.key", defaultValue = AdminAuth.UNSET_DEFAULT)
    String adminKey;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response dashboard(@HeaderParam("X-Restlink-Admin-Key") String headerKey,
                              @QueryParam("key") String queryKey) {
        Response denied = requireAdmin(firstKey(headerKey, queryKey));
        if (denied != null) return denied;
        Map<String, String> vars = htmlVars(firstKey(headerKey, queryKey));
        return Response.ok(pages.page("index.html", vars)).type(MediaType.TEXT_HTML).build();
    }

    @GET
    @Path("/partial/core-status")
    @Produces(MediaType.TEXT_HTML)
    public Response coreStatusPartial(@HeaderParam("X-Restlink-Admin-Key") String headerKey,
                                      @QueryParam("key") String queryKey) {
        Response denied = requireAdmin(firstKey(headerKey, queryKey));
        if (denied != null) return denied;
        return Response.ok(pages.partial("core-status.html", htmlVars(firstKey(headerKey, queryKey))))
                .type(MediaType.TEXT_HTML).build();
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@HeaderParam("X-Restlink-Admin-Key") String key) {
        Response denied = requireAdmin(key);
        if (denied != null) return denied;
        return Response.ok(statusMap()).build();
    }

    private Map<String, Object> statusMap() {
        Map<String, Object> providers = new LinkedHashMap<>();
        for (Map.Entry<CoreProvider, CoreBankingPort> e : router.ports().entrySet()) {
            providers.put(e.getKey().name(), Map.of(
                    "coreLive", e.getValue().coreLive(),
                    "capabilities", e.getValue().capabilities().caps().stream()
                            .map(Enum::name).sorted().toList()));
        }
        Map<String, Object> temporalStatus = new LinkedHashMap<>();
        temporalStatus.put("target", temporal.target());
        temporalStatus.put("namespace", temporal.namespace());
        temporalStatus.put("temporalLive", temporal.temporalLive());
        temporalStatus.put("workerPolling", temporal.workerPolling());
        temporalStatus.put("moneyReady", temporal.moneyReady());
        temporalStatus.put("lastError", temporal.lastError() == null ? "" : temporal.lastError());

        Map<String, Object> sagaCounts = new LinkedHashMap<>();
        for (PaymentState st : PaymentState.values()) {
            long n = sagas.countByState(st.name());
            if (n > 0) sagaCounts.put(st.name(), n);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", cluster.localNode());
        out.put("ispnMode", cluster.mode());
        out.put("members", cluster.members());
        out.put("pending", core.pending().size());
        out.put("idemClaims", core.idempotency().claimCount());
        out.put("defaultProvider", router.defaultProvider().name());
        out.put("providers", providers);
        out.put("profiles", customers.profileCount());
        out.put("profileDurable", customers.durable());
        out.put("profileTtl", "none");
        out.put("profileCacheMax", customers.cacheMax());
        out.put("profileHeapCached", customers.cacheSize());
        out.put("temporal", temporalStatus);
        out.put("paymentSagas", sagaCounts);
        out.put("inboxPending", sagas.inboxPendingCount());
        return out;
    }

    private Map<String, String> htmlVars(String token) {
        Map<String, Object> st = statusMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> temporalStatus = (Map<String, Object>) st.get("temporal");
        @SuppressWarnings("unchecked")
        Map<String, Object> providers = (Map<String, Object>) st.get("providers");
        boolean anyLive = providers.values().stream().anyMatch(v -> {
            if (v instanceof Map<?, ?> m) {
                Object live = m.get("coreLive");
                return Boolean.TRUE.equals(live);
            }
            return false;
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> sagasMap = (Map<String, Object>) st.get("paymentSagas");
        String sagaText = sagasMap.isEmpty() ? "none"
                : sagasMap.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "));
        String coreLive = providers.entrySet().stream()
                .map(e -> {
                    Object live = e.getValue() instanceof Map<?, ?> m ? m.get("coreLive") : "?";
                    return e.getKey() + "=" + live;
                })
                .collect(Collectors.joining(" "));
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("{{TOKEN}}", token == null ? "" : token);
        vars.put("{{NODE}}", AdminPageRenderer.esc(String.valueOf(st.get("node"))));
        vars.put("{{ISPN_MODE}}", AdminPageRenderer.esc(String.valueOf(st.get("ispnMode"))));
        vars.put("{{MONEY_READY}}", String.valueOf(temporalStatus.get("moneyReady")));
        vars.put("{{TEMPORAL_LIVE}}", String.valueOf(temporalStatus.get("temporalLive")));
        vars.put("{{WORKER_POLLING}}", String.valueOf(temporalStatus.get("workerPolling")));
        vars.put("{{CORE_LIVE}}", AdminPageRenderer.esc(coreLive.isBlank() ? "none" : coreLive));
        vars.put("{{ANY_CORE_LIVE}}", String.valueOf(anyLive));
        vars.put("{{SAGA_COUNTS}}", AdminPageRenderer.esc(sagaText));
        vars.put("{{PROFILES}}", String.valueOf(st.get("profiles")));
        vars.put("{{CORE_STATUS}}", "");
        return vars;
    }

    private Response requireAdmin(String key) {
        String reason = AdminAuth.denyReason(adminKey, key);
        if (reason == null) return null;
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", reason)).build();
    }

    private static String firstKey(String header, String query) {
        if (header != null && !header.isBlank()) return header;
        return query;
    }
}
