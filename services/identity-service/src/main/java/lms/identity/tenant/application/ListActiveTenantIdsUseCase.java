package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Id các tenant ACTIVE. Dùng cho việc chưa biết tenant phải tra lần lượt từng tenant (ví dụ đăng nhập ngoài tìm hồ sơ
 * độc giả theo email trước khi chọn tenant); mỗi lần tra chạy trong TenantContext của đúng tenant đó.
 */
public class ListActiveTenantIdsUseCase {

    private final TenantRepository tenants;

    public ListActiveTenantIdsUseCase(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<UUID> execute() {
        return tenants.findAll().stream().filter(Tenant::isActive).map(Tenant::id).toList();
    }
}
