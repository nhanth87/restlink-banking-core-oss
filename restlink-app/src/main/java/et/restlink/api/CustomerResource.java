package et.restlink.api;

import et.restlink.profile.CustomerDirectory;
import et.restlink.profile.CustomerDirectory.CustomerHandle;
import et.restlink.profile.CustomerDirectory.CustomerSpec;
import et.restlink.profile.CustomerProfile;
import et.restlink.spi.command.CreateCustomer;
import et.restlink.core.CoreBankingService;

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

import et.restlink.kyc.KycGate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Onboard / look up a customer. Every call to {@code POST /customers} provisions a dedicated
 * profile row <em>and</em> a dedicated CMP entity for that customer — never shared.
 */
@Path("/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerResource {

    @Inject
    CustomerDirectory customers;
    @Inject
    CoreBankingService core;

    @ConfigProperty(name = "restlink.admin.key", defaultValue = AdminAuth.UNSET_DEFAULT)
    String adminKey;

    public record OnboardReq(String tenantId, String customerId, String firstName, String lastName,
                             String mobileNo, String kycId, String currency) {}
    public record KycDecisionReq(String status) {}
    public record KycDocumentReq(String documentType, String reference) {}

    @POST
    public Response onboard(OnboardReq r) {
        if (r == null || r.tenantId() == null || r.customerId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "tenantId and customerId required")).build();
        }
        CustomerHandle handle = customers.ensure(r.tenantId(), r.customerId(),
                new CustomerSpec(r.firstName(), r.lastName(), r.mobileNo(), r.kycId(),
                        null, r.currency(), "ACTIVE"));
        String requestId = "REQ-" + UUID.randomUUID();
        handle.cmp().recordRequest(requestId);
        core.submit(new CreateCustomer(requestId, r.tenantId(), r.firstName(), r.lastName(),
                r.mobileNo(), r.kycId()));
        return Response.accepted(toJson(handle, requestId)).build();
    }

    @GET
    @Path("/{tenantId}/{customerId}")
    public Response get(@PathParam("tenantId") String tenantId,
                        @PathParam("customerId") String customerId) {
        Optional<CustomerHandle> found = customers.get(tenantId, customerId);
        if (found.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "no profile/cmp for " + tenantId + ":" + customerId)).build();
        }
        return Response.ok(toJson(found.get(), null)).build();
    }

    @POST
    @Path("/{tenantId}/{customerId}/kyc")
    public Response decideKyc(@HeaderParam("X-Restlink-Admin-Key") String key,
                              @PathParam("tenantId") String tenantId,
                              @PathParam("customerId") String customerId,
                              KycDecisionReq r) {
        String reason = AdminAuth.denyReason(adminKey, key);
        if (reason != null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", reason)).build();
        }
        if (r == null || !KycGate.validStatus(r.status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "status must be PENDING, APPROVED or REJECTED")).build();
        }
        Optional<CustomerHandle> updated = customers.setKyc(tenantId, customerId,
                KycGate.normalize(r.status()), null, null);
        if (updated.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "no profile/cmp for " + tenantId + ":" + customerId)).build();
        }
        return Response.ok(toJson(updated.get(), null)).build();
    }

    @POST
    @Path("/{tenantId}/{customerId}/kyc-documents")
    public Response uploadKycStub(@PathParam("tenantId") String tenantId,
                                  @PathParam("customerId") String customerId,
                                  KycDocumentReq r) {
        if (r == null || r.documentType() == null || r.documentType().isBlank()
                || r.reference() == null || r.reference().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "documentType and reference required")).build();
        }
        Optional<CustomerHandle> updated = customers.setKyc(tenantId, customerId,
                null, r.documentType().trim(), r.reference().trim());
        if (updated.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "no profile/cmp for " + tenantId + ":" + customerId)).build();
        }
        return Response.accepted(toJson(updated.get(), null)).build();
    }

    private static Map<String, Object> toJson(CustomerHandle h, String requestId) {
        CustomerProfile p = h.profile();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("tenantId", p.getTenantId());
        profile.put("customerId", p.getCustomerId());
        profile.put("firstName", p.getFirstName());
        profile.put("lastName", p.getLastName());
        profile.put("mobileNo", p.getMobileNo());
        profile.put("kycId", p.getKycId());
        profile.put("kycStatus", p.getKycStatus() == null ? "PENDING" : p.getKycStatus());
        profile.put("kycDocumentType", p.getKycDocumentType());
        profile.put("kycDocumentRef", p.getKycDocumentRef());
        profile.put("provider", p.getProvider());
        profile.put("defaultCurrency", p.getDefaultCurrency());
        profile.put("status", p.getStatus());

        Map<String, Object> cmp = new LinkedHashMap<>();
        cmp.put("entityId", h.cmp().entityId());
        cmp.put("lastRequestId", h.cmp().lastRequestId());
        cmp.put("lastAccountId", h.cmp().lastAccountId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", h.key());
        out.put("profile", profile);
        out.put("cmp", cmp);
        if (requestId != null) out.put("requestId", requestId);
        return out;
    }
}
