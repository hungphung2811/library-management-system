package lms.api.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tạo/sửa vị trí kệ: mã (duy nhất theo tenant, không phân biệt hoa thường) và mô tả. */
public record SaveShelfLocationRequest(
        @NotBlank @Size(max = 64) String code,
        @Size(max = 500) String description) {
}
