package lms.catalog.catalog.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lms.catalog.catalog.domain.ReferenceExtras;

/** Thân tạo/sửa tác giả. PUT thay toàn bộ: thiếu trường bổ sung nghĩa là xóa. */
public record SaveAuthorRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String description,
        @Min(1) @Max(3000) Integer birthYear,
        @Min(1) @Max(3000) Integer deathYear,
        @Size(max = 100) String nationality,
        @Size(max = 500) @Pattern(regexp = "(?i)https?://.+", message = "chỉ chấp nhận http/https") String photoUrl,
        @Size(max = 500) @Pattern(regexp = "(?i)https?://.+", message = "chỉ chấp nhận http/https") String website) {

    public SaveAuthorRequest(String name, String description) {
        this(name, description, null, null, null, null, null);
    }

    public ReferenceExtras extras() {
        return ReferenceExtras.author(birthYear, deathYear, nationality, photoUrl, website);
    }
}
