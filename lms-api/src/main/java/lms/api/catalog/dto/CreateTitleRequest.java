package lms.api.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** publisherId, categoryId, authorIds là tùy chọn (danh mục chuẩn); chuỗi author vẫn bắt buộc như trước. */
public record CreateTitleRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String author,
        @Size(max = 20) String isbn,
        @NotNull @DecimalMin("0") BigDecimal coverPrice,
        UUID publisherId,
        UUID categoryId,
        @Size(max = 20) List<UUID> authorIds,
        @Size(max = 255) String subtitle,
        @Min(1000) @Max(3000) Integer publicationYear,
        @Size(max = 50) String edition,
        @Pattern(regexp = "[a-z]{2,8}", message = "phải gồm 2 đến 8 chữ cái thường") String language,
        @Min(1) @Max(20000) Integer pageCount,
        @Pattern(regexp = "PAPERBACK|HARDCOVER|EBOOK", message = "phải là PAPERBACK, HARDCOVER hoặc EBOOK") String format,
        @Size(max = 255) String translator,
        @Size(max = 255) String series,
        @Min(1) Integer seriesNumber,
        @Size(max = 500) @Pattern(regexp = "(?i)https?://.+", message = "chỉ chấp nhận http/https") String coverImageUrl,
        @Size(max = 10) List<@Size(max = 50) String> tags) {

    /** Dạng 7 trường (chưa có thông tin bổ sung). */
    public CreateTitleRequest(String name, String author, String isbn, BigDecimal coverPrice, UUID publisherId, UUID categoryId,
               List<UUID> authorIds) {
        this(name, author, isbn, coverPrice, publisherId, categoryId, authorIds, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    public CreateTitleRequest(String name, String author, String isbn, BigDecimal coverPrice) {
        this(name, author, isbn, coverPrice, null, null, null);
    }
}
