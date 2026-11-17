package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.Tenant;

/**
 * Tenant mà người dùng truy cập được (trả cho client để chọn/đổi tenant). modules: module đang bật, chỉ để FE hiển thị
 * (quyết định chặn dựa vào claim {@code modules} của token, ADR-0009).
 */
public record TenantSummary(UUID id, String code, String name, List<String> modules) {

    public TenantSummary {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    public TenantSummary(UUID id, String code, String name) {
        this(id, code, name, List.of());
    }

    static TenantSummary of(Tenant tenant, List<String> modules) {
        return new TenantSummary(tenant.id(), tenant.code(), tenant.name(), modules);
    }
}
