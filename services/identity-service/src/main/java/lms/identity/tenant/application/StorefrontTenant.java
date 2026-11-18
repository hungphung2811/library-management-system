package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;

/**
 * Tenant khả dụng cho storefront công khai (ADR-0011): ACTIVE và bật SALES hoặc LENDING (ADR-0023). modules: mã module đang bật.
 * allowedOrigins: origin đang bật của tenant, đã chuẩn hóa (ADR-0013; không đưa vào claim JWT).
 */
public record StorefrontTenant(UUID id, String code, String name, List<String> modules, List<String> allowedOrigins) {

    public StorefrontTenant {
        modules = List.copyOf(modules);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    public StorefrontTenant(UUID id, String code, String name, List<String> modules) {
        this(id, code, name, modules, List.of());
    }
}
