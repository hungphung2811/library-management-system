package lms.identity.tenant.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thêm người dùng vào tenant (membership ACTIVE). Idempotent: đã có membership (kể cả DISABLED) thì giữ nguyên trạng
 * thái, không tự bật lại membership đã bị tắt. Có departmentId thì ghi phòng ban vào membership (tạo mới hoặc cập nhật
 * membership đã có); departmentId null không xóa phòng ban đang có.
 */
public class AddMembershipUseCase {

    private final TenantRepository tenants;
    private final MembershipRepository memberships;

    public AddMembershipUseCase(TenantRepository tenants, MembershipRepository memberships) {
        this.tenants = tenants;
        this.memberships = memberships;
    }

    /** @throws TenantNotFoundException nếu tenant không tồn tại */
    @Transactional
    public void execute(UUID userId, UUID tenantId) {
        execute(userId, tenantId, null);
    }

    /** @throws TenantNotFoundException nếu tenant không tồn tại */
    @Transactional
    public void execute(UUID userId, UUID tenantId, UUID departmentId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw new TenantNotFoundException("Không tìm thấy tenant " + tenantId);
        }
        Optional<Membership> existing = memberships.find(userId, tenantId);
        if (existing.isEmpty()) {
            memberships.save(Membership.create(userId, tenantId, departmentId));
            return;
        }
        Membership membership = existing.get();
        if (departmentId != null && !departmentId.equals(membership.departmentId())) {
            membership.moveToDepartment(departmentId);
            memberships.save(membership);
        }
    }
}
