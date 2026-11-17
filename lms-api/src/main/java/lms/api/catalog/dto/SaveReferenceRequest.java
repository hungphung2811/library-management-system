package lms.api.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Tạo/sửa tác giả, nhà xuất bản, thể loại. parentId chỉ dùng cho thể loại (cây tối đa 2 cấp), các loại khác bỏ qua. */
public record SaveReferenceRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String description,
        UUID parentId) {
}
