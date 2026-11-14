package lms.identity.department.application;

import lms.identity.department.domain.DepartmentStatus;
import java.util.List;
import java.util.UUID;

/** Một nút của cây phòng ban. */
public record DepartmentNode(UUID id, String code, String name, DepartmentStatus status, List<DepartmentNode> children) {
}
