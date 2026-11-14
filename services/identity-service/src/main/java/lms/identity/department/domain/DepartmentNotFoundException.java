package lms.identity.department.domain;

import java.util.UUID;

public class DepartmentNotFoundException extends RuntimeException {
    public DepartmentNotFoundException(UUID id) {
        super("Không tìm thấy phòng ban: " + id);
    }
}
