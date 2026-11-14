package lms.identity.user.application;

import java.util.UUID;

/** Cổng sang context department: phòng ban có tồn tại và đang hoạt động không. */
public interface DepartmentLookupPort {
    boolean existsActive(UUID departmentId);
}
