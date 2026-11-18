package lms.catalog.catalog.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lms.catalog.catalog.domain.ReferenceExtras;

/** Thân tạo/sửa nhà xuất bản. PUT thay toàn bộ: thiếu trường bổ sung nghĩa là xóa. */
public record SavePublisherRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String description,
        @Size(max = 100) String country,
        @Min(1400) @Max(3000) Integer foundedYear,
        @Size(max = 500) @Pattern(regexp = "(?i)https?://.+", message = "chỉ chấp nhận http/https") String website,
        @Size(max = 500) String address) {

    public SavePublisherRequest(String name, String description) {
        this(name, description, null, null, null, null);
    }

    public ReferenceExtras extras() {
        return ReferenceExtras.publisher(country, foundedYear, website, address);
    }
}
