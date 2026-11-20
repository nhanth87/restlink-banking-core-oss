package et.restlink.api;

import et.restlink.persist.ApiAuditEntry;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persist mutating public/admin calls. Body is never stored (PAN / secret risk).
 */
@Provider
public class ApiAuditFilter implements ContainerResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiAuditFilter.class);

    @Inject
    ApiAuditStore store;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!ApiAudit.mutating(request.getMethod())) {
            return;
        }
        String path = request.getUriInfo().getPath();
        if (path != null && (path.startsWith("q/") || path.startsWith("/q/"))) {
            return;
        }
        Object actorProp = request.getProperty(ApiAudit.ACTOR_PROPERTY);
        String actor = actorProp == null ? actorFromHeaders(request) : actorProp.toString();
        String tenant = ApiAudit.tenantFromPath(path);
        String ip = ApiAudit.clientIp(
                request.getHeaderString("X-Forwarded-For"),
                request.getUriInfo().getRequestUri().getHost());
        try {
            store.record(new ApiAuditEntry.New(
                    tenant,
                    actor,
                    request.getMethod(),
                    ApiAudit.sanitizePath(path),
                    ip,
                    response.getStatus()));
        } catch (RuntimeException e) {
            LOG.warn("api audit persist failed path={}: {}", path, e.toString());
        }
    }

    private static String actorFromHeaders(ContainerRequestContext request) {
        String admin = request.getHeaderString(ApiAudit.HEADER_ADMIN);
        if (admin != null && !admin.isBlank()) {
            return "admin";
        }
        return ApiAudit.actorId(false, request.getHeaderString(ApiAuth.HEADER));
    }
}
