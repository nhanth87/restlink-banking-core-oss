package et.restlink.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * Fail-closed auth on public customer/wallet APIs. Admin key (if valid) is accepted as superuser.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiAuthFilter implements ContainerRequestFilter {

    @ConfigProperty(name = "restlink.api.key", defaultValue = ApiAuth.UNSET_DEFAULT)
    String apiKey;

    @ConfigProperty(name = "restlink.admin.key", defaultValue = AdminAuth.UNSET_DEFAULT)
    String adminKey;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (!ApiAuth.covers(path)) {
            return;
        }
        String providedApi = ctx.getHeaderString(ApiAuth.HEADER);
        String providedAdmin = ctx.getHeaderString("X-Restlink-Admin-Key");
        String reason = ApiAuth.denyReason(apiKey, providedApi, adminKey, providedAdmin);
        if (reason == null) {
            ctx.setProperty(ApiAudit.ACTOR_PROPERTY, ApiAudit.actorId(
                    AdminAuth.denyReason(adminKey, providedAdmin) == null,
                    providedApi));
            return;
        }
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", reason))
                .build());
    }
}
