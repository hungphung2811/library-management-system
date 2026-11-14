package lms.identity.department.application;

import lms.identity.department.domain.DepartmentStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeDepartmentStatusRequest(@NotNull DepartmentStatus status) {
}
