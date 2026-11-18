package lms.catalog.inventory.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BookCopyRepository {

    BookCopy save(BookCopy copy);

    Optional<BookCopy> findById(UUID id);

    /** Đọc bản sao và khóa dòng (SELECT ... FOR UPDATE) để hai thao tác đổi trạng thái đồng thời không đè nhau. */
    Optional<BookCopy> findByIdForUpdate(UUID id);

    Optional<CopyDetails> findDetailsById(UUID id);

    Optional<CopyDetails> findDetailsByBarcode(String barcode);

    boolean existsByBarcode(String barcode);

    boolean titleExists(UUID titleId);

    List<BookCopy> findByTitleId(UUID titleId);

    /** Số bản sao ở trạng thái cho trước theo từng đầu sách; đầu sách không có bản sao nào thì không xuất hiện trong map. */
    Map<UUID, Long> countByTitleIds(Collection<UUID> titleIds, CopyStatus status);

    Set<UUID> findTitleIdsHavingStatus(CopyStatus status);

    /** Một dòng đếm bản sao theo tác phẩm và ngôn ngữ của ấn bản ({@code language} null = ấn bản chưa khai ngôn ngữ). */
    record WorkLanguageCount(UUID workId, String language, long count) {
    }

    /**
     * Số bản sao ở trạng thái cho trước theo (tác phẩm, ngôn ngữ ấn bản): một truy vấn cho cả tập tác phẩm (ADR-0026 3.2).
     * Ấn bản chưa gắn tác phẩm (work_id NULL) không xuất hiện.
     */
    default List<WorkLanguageCount> countByWorkIds(Collection<UUID> workIds, CopyStatus status) {
        return List.of();
    }

    /** Id tác phẩm của các ấn bản (titleId -> workId), một truy vấn; ấn bản chưa gắn tác phẩm thì vắng. */
    default Map<UUID, UUID> findWorkIdsByTitleIds(Collection<UUID> titleIds) {
        return Map.of();
    }

    /** Tác phẩm và ngôn ngữ của một ấn bản (cả hai có thể null). */
    record TitleWork(UUID workId, String language) {
    }

    /**
     * Tác phẩm và ngôn ngữ của các ấn bản (titleId -> TitleWork), một truy vấn cho cả tập (không N+1). Mặc định suy từ
     * {@link #findWorkIdsByTitleIds} với language null để tương thích các bản cài đặt cũ.
     */
    default Map<UUID, TitleWork> findTitleWorksByTitleIds(Collection<UUID> titleIds) {
        Map<UUID, TitleWork> result = new java.util.HashMap<>();
        findWorkIdsByTitleIds(titleIds).forEach((t, w) -> result.put(t, new TitleWork(w, null)));
        return result;
    }

    /** Tra theo lô: bản sao cùng tenant kèm tên/ISBN đầu sách; id không có hoặc khác tenant thì vắng. */
    default List<CopyLookupItem> findLookupByIds(Collection<UUID> ids) {
        return List.of();
    }

    /** Mã vạch (khớp đúng chuỗi) thuộc tập đã cho đã có trong tenant, một truy vấn (chia lô); dùng cho nhập hàng loạt. */
    default Set<String> findExistingBarcodes(Collection<String> barcodes) {
        Set<String> result = new java.util.HashSet<>();
        for (String b : barcodes) if (existsByBarcode(b)) result.add(b);
        return result;
    }

    /** Ghi mới cả loạt, không kiểm trùng; ném {@link CopyBatchConflictException} khi trùng mã vạch do ghi đồng thời. */
    default void insertAll(Collection<BookCopy> copies) {
        for (BookCopy c : copies) save(c);
    }

    /** Trạng thái active của vị trí kệ cùng tenant; empty nếu không có vị trí kệ đó. */
    default Optional<Boolean> findShelfLocationActive(UUID shelfLocationId) {
        return Optional.empty();
    }

    /** Mã vị trí kệ theo id (để hiển thị); id không có thì không xuất hiện trong map. */
    default Map<UUID, String> findShelfLocationCodes(Collection<UUID> shelfLocationIds) {
        return Map.of();
    }
}
