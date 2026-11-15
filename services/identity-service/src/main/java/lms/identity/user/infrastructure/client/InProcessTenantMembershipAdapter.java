package lms.identity.user.infrastructure.client;

import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.ChangeMembershipDepartmentUseCase;
import lms.identity.user.application.TenantMembershipPort;
import java.util.UUID;

/** Gọi API (tầng application) của context tenant trong cùng service. */
public class InProcessTenantMembershipAdapter implements TenantMembershipPort {

    private final AddMembershipUseCase addMembership;
    private final ChangeMembershipDepartmentUseCase changeDepartment;

    public InProcessTenantMembershipAdapter(AddMembershipUseCase addMembership,
                                            ChangeMembershipDepartmentUseCase changeDepartment) {
        this.addMembership = addMembership;
        this.changeDepartment = changeDepartment;
    }

    @Override
    public void join(UUID userId, UUID tenantId, UUID departmentId) {
        addMembership.execute(userId, tenantId, departmentId);
    }

    @Override
    public void changeDepartment(UUID userId, UUID tenantId, UUID departmentId) {
        changeDepartment.execute(userId, tenantId, departmentId);
    }
}
