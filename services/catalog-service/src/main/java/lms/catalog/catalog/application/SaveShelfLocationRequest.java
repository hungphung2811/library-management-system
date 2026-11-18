package lms.catalog.catalog.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lms.catalog.catalog.domain.ReferenceExtras;

/**
 * Thân tạo/sửa vị trí kệ: mã (duy nhất theo tenant, không phân biệt hoa thường), mô tả, và thông tin bổ sung tùy chọn
 * (tầng, khu, sức chứa). PUT thay toàn bộ: thiếu trường bổ sung nghĩa là xóa.
 */
public record SaveShelfLocationRequest(
        @NotBlank @Size(max = 64) String code,
        @Size(max = 500) String description,
        @Size(max = 20) String floor,
        @Size(max = 50) String zone,
        @Min(1) @Max(100000) Integer capacity) {

    public SaveShelfLocationRequest(String code, String description) {
        this(code, description, null, null, null);
    }

    public ReferenceExtras extras() {
        return ReferenceExtras.shelf(floor, zone, capacity);
    }
}
