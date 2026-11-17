package lms.identity.tenant.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;

/** Tra tenant theo mã (bảng tenants toàn cục, không cần TenantContext). Dùng để seed demo idempotent. */
public class FindTenantByCodeQuery {

    /** Tóm tắt tenant, không lộ entity/domain ra ngoài context. */
    public record Found(UUID id, String code, String name, TenantStatus status) {
    }

    private final TenantRepository tenants;

    public FindTenantByCodeQuery(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public Optional<Found> execute(String code) {
        return tenants.findByCode(code).map(t -> new Found(t.id(), t.code(), t.name(), t.status()));
    }
}
