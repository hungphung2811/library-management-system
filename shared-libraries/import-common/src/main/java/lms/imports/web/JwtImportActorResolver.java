package lms.imports.web;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lms.imports.engine.ImportActor;
import lms.security.JwtClaims;
import lms.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Tenant lấy từ {@link TenantContext} (đã bind từ claim {@code tenantId}, hoặc từ {@code X-Tenant-Id} khi là platform
 * admin); userId là {@code sub}; module là claim {@code modules}.
 */
public final class JwtImportActorResolver implements ImportActorResolver {

    @Override
    public ImportActor current() {
        UUID tenant = TenantContext.require();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String sub = jwt.getToken().getSubject();
            Set<String> modules = new HashSet<>();
            if (jwt.getToken().getClaim(JwtClaims.MODULES) instanceof Collection<?> values) {
                for (Object v : values) modules.add(String.valueOf(v));
            }
            boolean platformAdmin = Boolean.TRUE.equals(jwt.getToken().getClaim(JwtClaims.PLATFORM_ADMIN));
            return new ImportActor(tenant, sub == null ? "unknown" : sub, modules, platformAdmin);
        }
        return new ImportActor(tenant, auth == null ? "anonymous" : String.valueOf(auth.getName()), Set.of(), false);
    }
}
