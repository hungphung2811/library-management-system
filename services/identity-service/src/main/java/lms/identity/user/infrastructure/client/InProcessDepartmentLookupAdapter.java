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

    @Override
    public java.util.Map<String, UUID> activeIdsByCode() {
        java.util.Map<String, UUID> result = new java.util.HashMap<>();
        getDepartment.findAll().stream().filter(DepartmentResponse::active)
                .forEach(d -> result.put(d.code().toUpperCase(java.util.Locale.ROOT), d.id()));
        return result;
    }

    @Override
    public java.util.Map<UUID, String> codesById() {
        java.util.Map<UUID, String> result = new java.util.HashMap<>();
        getDepartment.findAll().forEach(d -> result.put(d.id(), d.code()));
        return result;
    }
}
