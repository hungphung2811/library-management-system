package lms.integration.catalog.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DTO v1 của nhóm books: ALLOWLIST riêng của integration-api, không phải DTO của catalog. Không có tenantId, số bản
 * sao (availableCopies), previewText hay bất kỳ trường nội bộ nào; thêm trường là thay đổi hợp đồng phải ghi ở
 * docs/api/integration-api.md. {@code GET /books/{id}} có authors/publisher/category nhưng không có description;
 * các lookup có description nhưng không có authors/publisher/category (null khi nguồn không cung cấp).
 */
public final class BookDtos {

    private BookDtos() {
    }

    public static final int MAX_LOOKUP = 100;

    public record AuthorView(UUID id, String name) {
    }

    public record BookView(UUID id, String name, String subtitle, String author, List<AuthorView> authors, String isbn,
                           BigDecimal coverPrice, String description, UUID publisherId, String publisherName,
                           UUID categoryId, String categoryName, Integer publicationYear, String edition,
                           String language, Integer pageCount, String format, String translator, String series,
                           Integer seriesNumber, String coverImageUrl, List<String> tags, UUID workId,
                           String workTitle, Integer partNumber, String partLabel) {
    }

    public record LookupRequest(@NotEmpty @Size(max = MAX_LOOKUP) List<@NotNull UUID> ids) {
    }

    public record LookupByIsbnRequest(
            @NotEmpty @Size(max = MAX_LOOKUP) List<@NotNull @Size(min = 1, max = 20) String> isbns) {
    }
}
