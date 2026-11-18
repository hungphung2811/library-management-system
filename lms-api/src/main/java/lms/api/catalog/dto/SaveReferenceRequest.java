package lms.api.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Tạo/sửa tác giả, nhà xuất bản, thể loại. parentId chỉ dùng cho thể loại (cây tối đa 2 cấp), các loại khác bỏ qua.
 * Thông tin bổ sung tùy chọn theo loại: tác giả (birthYear, deathYear, nationality, photoUrl, website), nhà xuất bản
 * (country, foundedYear, website, address); trường không thuộc loại của đường dẫn bị catalog bỏ qua. PUT thay toàn bộ:
 * thiếu trường bổ sung nghĩa là xóa.
 */
public record SaveReferenceRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String description,
        UUID parentId,
        @Min(1) @Max(3000) Integer birthYear,
        @Min(1) @Max(3000) Integer deathYear,
        @Size(max = 100) String nationality,
        @Size(max = 500) @Pattern(regexp = "(?i)https?://.+", message = "chỉ chấp nhận http/https") String photoUrl,
        @Size(max = 500) @Pattern(regexp = "(?i)https?://.+", message = "chỉ chấp nhận http/https") String website,
        @Size(max = 100) String country,
        @Min(1400) @Max(3000) Integer foundedYear,
        @Size(max = 500) String address) {

    public SaveReferenceRequest(String name, String description, UUID parentId) {
        this(name, description, parentId, null, null, null, null, null, null, null, null);
    }
}
