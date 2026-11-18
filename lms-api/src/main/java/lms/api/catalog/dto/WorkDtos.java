package lms.api.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Hợp đồng /api/works cho FE (ADR-0026, docs/api/catalog-works-series.md). Tác phẩm = nội dung trừu tượng; ấn bản =
 * đầu sách ({@code titleId}). Kiểu enum (kind, format) để chuỗi, catalog-service kiểm giá trị.
 */
public final class WorkDtos {

    private WorkDtos() {
    }

    /** Tìm tác phẩm: page từ 0, size 1..100 (mặc định 20 ở catalog). */
    public record WorkSearchQuery(String keyword, UUID seriesId, UUID categoryId, UUID authorId, Integer page,
                                  Integer size) {
    }

    /** Thân tạo/sửa tác phẩm (PUT thay toàn bộ). kind: SINGLE (mặc định) hoặc COLLECTION. */
    public record SaveWorkRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 255) String originalTitle,
            @Pattern(regexp = "[a-z]{2,8}", message = "phải gồm 2 đến 8 chữ cái thường") String originalLanguage,
            @Size(max = 255) String author,
            @Size(max = 20) List<UUID> authorIds,
            UUID categoryId,
            UUID seriesId,
            @Min(1) Integer seriesNumber,
            @Size(max = 50) String seriesLabel,
            @Size(max = 500) String description,
            @Pattern(regexp = "SINGLE|COLLECTION", message = "phải là SINGLE hoặc COLLECTION") String kind) {
    }

    /** Gộp các tác phẩm nguồn (tối đa 20) vào tác phẩm đích ở đường dẫn. */
    public record MergeWorksRequest(@NotEmpty @Size(max = 20) List<UUID> sourceWorkIds) {
    }

    /** Đổi tác phẩm của ấn bản: đúng một trong workId (gắn vào tác phẩm có sẵn) hoặc newWork (tách ra tác phẩm mới). */
    public record ChangeEditionWorkRequest(UUID workId, @Valid SaveWorkRequest newWork) {
    }

    public record WorkSummaryView(UUID id, String title, String originalTitle, String author, UUID seriesId,
                                  String seriesName, Integer seriesNumber, String kind, long editionCount,
                                  long availableCopies, String coverImageUrl) {
    }

    /** Trang tác phẩm (page đếm từ 0). */
    public record WorkPageView(List<WorkSummaryView> items, long total, int page, int size) {
    }

    public record EditionSummaryView(UUID titleId, String isbn, String name, String format, String language,
                                     String translator, String edition, Integer publicationYear, Integer partNumber,
                                     String partLabel, BigDecimal coverPrice, String coverImageUrl,
                                     long availableCopies) {
    }

    public record WorkDetailView(UUID id, String title, String originalTitle, String originalLanguage, String author,
                                 List<AuthorRefView> authors, String description, UUID categoryId,
                                 String categoryName, UUID seriesId, String seriesName, Integer seriesNumber,
                                 String seriesLabel, String kind, UUID tenantId, List<EditionSummaryView> editions) {
    }
}
