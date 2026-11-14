package lms.identity.department.application;

import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentStatus;
import java.util.UUID;

public record DepartmentResponse(UUID id, String code, String name, UUID parentId, DepartmentStatus status) {

    /** Dành cho context khác kiểm tra trạng thái mà không phải phụ thuộc enum của domain. */
    public boolean active() {
        return status == DepartmentStatus.ACTIVE;
    }

    public static DepartmentResponse from(Department d) {
        return new DepartmentResponse(d.id(), d.code(), d.name(), d.parentId(), d.status());
    }
}
