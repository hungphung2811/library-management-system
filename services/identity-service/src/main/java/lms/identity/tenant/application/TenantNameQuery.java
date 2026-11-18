package lms.identity.tenant.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/** Tên hiển thị của tenant theo id (bảng toàn cục, không cần TenantContext). Tenant không tồn tại thì rỗng. */
public class TenantNameQuery {

    private final TenantRepository tenants;

    public TenantNameQuery(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public Optional<String> nameOf(UUID tenantId) {
        if (tenantId == null) return Optional.empty();
        return tenants.findById(tenantId).map(t -> t.name());
    }

    /** Mã tenant theo id (manifest xuất dữ liệu, ADR-0031); tenant không tồn tại thì rỗng. */
    @Transactional(readOnly = true)
    public Optional<String> codeOf(UUID tenantId) {
        if (tenantId == null) return Optional.empty();
        return tenants.findById(tenantId).map(t -> t.code());
    }
}
