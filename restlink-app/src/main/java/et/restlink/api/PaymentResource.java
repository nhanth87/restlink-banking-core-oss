package et.restlink.api;

import et.restlink.payment.PaymentService;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * Durable payment status + audited operator recovery signals.
 * No "re-post money" endpoint — only reconcile / compensation retries.
 * All endpoints require {@code X-Restlink-Admin-Key} (status leaks coreTxnId / error text).
 */
@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentResource {

    @Inject
    PaymentService payments;

    @ConfigProperty(name = "restlink.admin.key", defaultValue = AdminAuth.UNSET_DEFAULT)
    String adminKey;

    @GET
    @Path("/{paymentId}")
    public Response status(@HeaderParam("X-Restlink-Admin-Key") String key,
                           @PathParam("paymentId") String paymentId) {
        Response denied = requireAdmin(key);
        if (denied != null) return denied;
        return payments.status(paymentId)
                .<Response>map(m -> Response.ok(m).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("paymentId", paymentId, "state", "UNKNOWN")).build());
    }

    @POST
    @Path("/{paymentId}/retry-reconcile")
    public Response retryReconcile(@HeaderParam("X-Restlink-Admin-Key") String key,
                                   @PathParam("paymentId") String paymentId) {
        Response denied = requireAdmin(key);
        if (denied != null) return denied;
        boolean ok = payments.signalRetryReconcile(paymentId);
        return ok ? Response.accepted(Map.of("signaled", "retryReconcile", "paymentId", paymentId)).build()
                : Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "signal failed", "paymentId", paymentId)).build();
    }

    @POST
    @Path("/{paymentId}/retry-compensation")
    public Response retryCompensation(@HeaderParam("X-Restlink-Admin-Key") String key,
                                      @PathParam("paymentId") String paymentId) {
        Response denied = requireAdmin(key);
        if (denied != null) return denied;
        boolean ok = payments.signalRetryCompensation(paymentId);
        return ok ? Response.accepted(Map.of("signaled", "retryCompensation", "paymentId", paymentId)).build()
                : Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "signal failed", "paymentId", paymentId)).build();
    }

    private Response requireAdmin(String key) {
        String reason = AdminAuth.denyReason(adminKey, key);
        if (reason == null) return null;
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", reason)).build();
    }
}
