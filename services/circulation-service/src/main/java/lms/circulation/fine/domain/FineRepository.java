package lms.circulation.fine.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FineRepository {

    Fine save(Fine fine);

    boolean exists(UUID borrowingId, UUID bookCopyId, FineReason reason);

    /** Mọi khoản phạt của độc giả, mới nhất trước. */
    List<Fine> findByReader(UUID readerId);

    /** Các khoản còn phải thu (UNPAID và REDUCED), khóa dòng để hai thủ thư thu đồng thời không thu trùng. */
    List<Fine> findUnpaidByReaderForUpdate(UUID readerId);

    /** Tổng số phải thu (payableAmount) của các khoản còn phải thu; khoản WAIVED và PAID không tính. */
    BigDecimal sumUnpaidByReader(UUID readerId);

    /** Khoản phạt theo id, khóa dòng (miễn/giảm, hoàn khoản). */
    Optional<Fine> findByIdForUpdate(UUID id);

    /** Khoản phạt theo (lượt mượn, bản sao, lý do), không khóa. */
    Optional<Fine> findByItemAndReason(UUID borrowingId, UUID bookCopyId, FineReason reason);
}
