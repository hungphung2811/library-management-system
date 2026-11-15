package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.InvalidTenantException;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đổi phòng ban của người dùng trong một tenant (tenant_memberships.department_id). departmentId null = bỏ phòng ban.
 * Việc kiểm tra phòng ban tồn tại/đang hoạt động thuộc về người gọi (context user dùng context department).
 */
public class ChangeMembershipDepartmentUseCase {

    private final MembershipRepository memberships;

    public ChangeMembershipDepartmentUseCase(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    /** @throws InvalidTenantException nếu người dùng không phải thành viên của tenant */
    @Transactional
    public void execute(UUID userId, UUID tenantId, UUID departmentId) {
        Membership membership = memberships.find(userId, tenantId)
                .orElseThrow(() -> new InvalidTenantException("Người dùng không phải thành viên của tenant"));
        membership.moveToDepartment(departmentId);
        memberships.save(membership);
    }
}
