package lms.circulation.fine.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface FineRepository {

    Fine save(Fine fine);

    boolean exists(UUID borrowingId, UUID bookCopyId, FineReason reason);

    /** Mọi khoản phạt của độc giả, mới nhất trước. */
    List<Fine> findByReader(UUID readerId);

    /** Các khoản chưa thanh toán, khóa dòng để hai thủ thư thu đồng thời không thu trùng. */
    List<Fine> findUnpaidByReaderForUpdate(UUID readerId);

    BigDecimal sumUnpaidByReader(UUID readerId);
}
