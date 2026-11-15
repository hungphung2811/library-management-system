package lms.circulation.tenant.application;

import java.util.UUID;
import lms.circulation.tenant.domain.KnownTenantStatus;
import org.springframework.transaction.annotation.Transactional;

/** Event TenantStatusChanged: cập nhật trạng thái trong danh bạ; tenant SUSPENDED bị việc nền UC14 bỏ qua. */
public class ChangeTenantStatusUseCase {

    private final KnownTenantsPort knownTenants;

    public ChangeTenantStatusUseCase(KnownTenantsPort knownTenants) {
        this.knownTenants = knownTenants;
    }

    @Transactional
    public void execute(UUID tenantId, KnownTenantStatus status) {
        if (tenantId == null || status == null) throw new IllegalArgumentException("Thiếu tenantId hoặc trạng thái");
        knownTenants.upsert(tenantId, status);
    }
}
