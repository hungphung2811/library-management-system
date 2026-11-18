package lms.imports.engine;

import java.util.Set;
import java.util.UUID;
import lms.imports.core.ImportContext;

/**
 * Người gọi: tenant đích, userId ({@code sub} của JWT) và module của tenant trong token. Platform admin dùng
 * {@code X-Tenant-Id}: tenant là tenant đích, userId là chính platform admin (ADR-0016).
 */
public record ImportActor(UUID tenantId, String userId, Set<String> modules, boolean platformAdmin) {

    public ImportActor {
        modules = modules == null ? Set.of() : Set.copyOf(modules);
    }

    public ImportContext context(UUID importId) {
        return new ImportContext(tenantId, userId, importId, modules, platformAdmin);
    }
}
