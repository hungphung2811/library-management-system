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
