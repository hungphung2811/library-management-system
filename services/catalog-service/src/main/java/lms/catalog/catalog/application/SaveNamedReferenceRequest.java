package lms.catalog.catalog.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Thân tạo/sửa tác giả và nhà xuất bản. */
public record SaveNamedReferenceRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String description) {
}
