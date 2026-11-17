package lms.api.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Sửa đầu sách: thay toàn bộ các trường (PUT). isbn có thể null/bỏ trống để xóa ISBN. publisherId, categoryId,
 * authorIds null/vắng nghĩa là gỡ phân loại chuẩn tương ứng (chuỗi author cũ không bị đổi bởi các trường này).
 */
public record UpdateTitleRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String author,
        @Size(max = 20) String isbn,
        @NotNull @DecimalMin("0") BigDecimal coverPrice,
        UUID publisherId,
        UUID categoryId,
        @Size(max = 20) List<UUID> authorIds) {

    public UpdateTitleRequest(String name, String author, String isbn, BigDecimal coverPrice) {
        this(name, author, isbn, coverPrice, null, null, null);
    }
}
