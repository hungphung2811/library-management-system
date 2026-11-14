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
}
