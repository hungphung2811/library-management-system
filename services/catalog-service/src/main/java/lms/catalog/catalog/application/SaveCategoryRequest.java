package lms.catalog.catalog.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Thân tạo/sửa thể loại; parentId null = thể loại gốc (cây tối đa 2 cấp). */
public record SaveCategoryRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String description,
        UUID parentId) {
}
