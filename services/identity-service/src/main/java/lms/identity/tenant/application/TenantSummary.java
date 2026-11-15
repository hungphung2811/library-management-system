package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.Tenant;

/** Tenant mà người dùng truy cập được (trả cho client để chọn/đổi tenant). */
public record TenantSummary(UUID id, String code, String name) {

    static TenantSummary of(Tenant tenant) {
        return new TenantSummary(tenant.id(), tenant.code(), tenant.name());
    }
}
