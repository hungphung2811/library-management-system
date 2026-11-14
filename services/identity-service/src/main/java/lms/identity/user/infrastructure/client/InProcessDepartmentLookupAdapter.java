package lms.identity.user.infrastructure.client;

import lms.identity.department.application.DepartmentResponse;
import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.user.application.DepartmentLookupPort;
import java.util.UUID;

/** Gọi API (tầng application) của context department trong cùng service. */
public class InProcessDepartmentLookupAdapter implements DepartmentLookupPort {

    private final GetDepartmentUseCase getDepartment;

    public InProcessDepartmentLookupAdapter(GetDepartmentUseCase getDepartment) {
        this.getDepartment = getDepartment;
    }

    @Override
    public boolean existsActive(UUID departmentId) {
        return getDepartment.find(departmentId).map(DepartmentResponse::active).orElse(false);
    }
}
