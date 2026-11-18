package lms.catalog.catalog.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.domain.TitleFormat;
import lms.catalog.catalog.domain.WorkKind;

/** Hợp đồng vào/ra của API tác phẩm (ADR-0026 mục 3.3). Gom một chỗ vì các record nhỏ và đi cùng nhau. */
public final class WorkDtos {

    private WorkDtos() {
    }

    /** Thân tạo/sửa tác phẩm (PUT thay toàn bộ). {@code author} trống mà có authorIds thì ghép tên tác giả chuẩn. */
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
            WorkKind kind) {
    }

    /** Thân gộp: các tác phẩm nguồn (tối đa 20) được gộp vào tác phẩm đích ở đường dẫn. */
    public record MergeWorksRequest(@NotEmpty @Size(max = 20) List<UUID> sourceWorkIds) {
    }

    /** Thân đổi tác phẩm của ấn bản: đúng một trong hai, {@code workId} (gắn vào tác phẩm có sẵn) hoặc {@code newWork} (tách ra tác phẩm mới). */
    public record ChangeEditionWorkRequest(UUID workId, @Valid SaveWorkRequest newWork) {
    }

    /** Tìm tác phẩm: keyword (tên, tên gốc, tác giả), seriesId, categoryId, authorId, page từ 0, size 1..100 (mặc định 20). */
    public record SearchWorksQuery(String keyword, UUID seriesId, UUID categoryId, UUID authorId, Integer page,
                                   Integer size) {

        public int pageOrDefault() {
            return page == null || page < 0 ? 0 : page;
        }

        public int sizeOrDefault() {
            return size == null || size < 1 ? 20 : Math.min(size, 100);
        }
    }

    /** Một dòng danh sách tác phẩm. {@code availableCopies} gộp mọi ấn bản (mọi ngôn ngữ); ảnh bìa lấy từ ấn bản đại diện. */
    public record WorkSummary(UUID id, String title, String originalTitle, String author, UUID seriesId,
                              String seriesName, Integer seriesNumber, WorkKind kind, long editionCount,
                              long availableCopies, String coverImageUrl) {
    }

    public record WorkPageResponse(List<WorkSummary> items, long total, int page, int size) {
    }

    /** Một ấn bản trong chi tiết tác phẩm. */
    public record EditionSummary(UUID titleId, String isbn, String name, TitleFormat format, String language,
                                 String translator, String edition, Integer publicationYear, Integer partNumber,
                                 String partLabel, BigDecimal coverPrice, String coverImageUrl, long availableCopies) {
    }

    /** Chi tiết tác phẩm kèm danh sách ấn bản và số bản sao sẵn có của từng ấn bản. */
    public record WorkDetail(UUID id, String title, String originalTitle, String originalLanguage, String author,
                             List<AuthorRef> authors, String description, UUID categoryId, String categoryName,
                             UUID seriesId, String seriesName, Integer seriesNumber, String seriesLabel,
                             WorkKind kind, UUID tenantId, List<EditionSummary> editions) {
    }
}
