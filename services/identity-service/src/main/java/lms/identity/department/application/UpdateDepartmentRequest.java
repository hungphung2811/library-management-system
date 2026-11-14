package lms.identity.department.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** parentId null nghĩa là chuyển lên làm phòng ban gốc. */
public record UpdateDepartmentRequest(@NotBlank @Size(max = 255) String name, UUID parentId) {
}
