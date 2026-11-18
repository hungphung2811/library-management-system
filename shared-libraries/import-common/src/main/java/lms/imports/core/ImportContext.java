package lms.imports.core;

import java.util.Set;
import java.util.UUID;

/**
 * Ngữ cảnh cho handler. Ở luồng confirm nền không có SecurityContext: {@code userId} lấy từ phiên, và luồng đã được đặt
 * {@code TenantContext.runAs(tenantId, ...)} trước khi gọi handler (handler mở transaction sau đó).
 *
 * @param modules       mã module đang bật của tenant trong token (ví dụ LENDING, SALES); platform admin coi như có mọi module
 * @param platformAdmin người gọi là platform admin
 */
public record ImportContext(UUID tenantId, String userId, UUID importId, Set<String> modules, boolean platformAdmin) {

    public ImportContext {
        modules = modules == null ? Set.of() : Set.copyOf(modules);
    }

    public boolean hasModule(String module) {
        return platformAdmin || modules.contains(module);
    }
}
