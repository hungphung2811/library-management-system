package lms.circulation.tenant.application;

import java.util.UUID;
import lms.circulation.borrowing.application.ProvisionDefaultBorrowRulesUseCase;
import lms.circulation.tenant.domain.KnownTenantStatus;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant mới được tạo ở identity (event TenantCreated): ghi vào danh bạ known_tenants (ACTIVE, để việc nền UC14 chạy
 * cho tenant này) và tạo quy tắc mượn mặc định cho tenant đó. Idempotent nên event giao lại an toàn.
 *
 * <p>Người gọi phải đặt TenantContext = tenantId TRƯỚC khi gọi (consumer dùng TenantAwareEventHandler), vì biến RLS
 * app.tenant_id chốt lúc transaction mượn kết nối. Tenant trong ngữ cảnh khác tenantId thì từ chối.
 */
public class RegisterTenantUseCase {

    private final KnownTenantsPort knownTenants;
    private final ProvisionDefaultBorrowRulesUseCase provisionBorrowRules;

    public RegisterTenantUseCase(KnownTenantsPort knownTenants, ProvisionDefaultBorrowRulesUseCase provisionBorrowRules) {
        this.knownTenants = knownTenants;
        this.provisionBorrowRules = provisionBorrowRules;
    }

    /** @return số quy tắc mượn vừa tạo (0 nếu tenant đã có đủ). */
    @Transactional
    public int execute(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId không được null");
        UUID current = TenantContext.require();
        if (!current.equals(tenantId)) {
            throw new IllegalStateException("Tenant trong ngữ cảnh khác tenant được đăng ký");
        }
        knownTenants.upsert(tenantId, KnownTenantStatus.ACTIVE);
        return provisionBorrowRules.execute();
    }
}
