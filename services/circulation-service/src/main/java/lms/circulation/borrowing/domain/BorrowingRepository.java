package lms.circulation.borrowing.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BorrowingRepository {
    Optional<BorrowingRecord> findById(BorrowingId id);

    /** Phiếu theo id chỉ khi thuộc userId: điều kiện sở hữu nằm trong truy vấn, không đọc phiếu người khác rồi mới so. */
    Optional<BorrowingRecord> findByIdAndUserId(BorrowingId id, UUID userId);

    /** Như findById nhưng khóa dòng để hai thủ thư xử lý cùng phiếu không đè nhau. */
    Optional<BorrowingRecord> findByIdForUpdate(BorrowingId id);

    /** Phiếu có lượt mượn đang mở của bản sao này (UC07/UC08 tra theo mã bản sao), khóa dòng. */
    Optional<BorrowingRecord> findOpenByCopyIdForUpdate(UUID bookCopyId);

    /** Phiếu mượn của độc giả, mới nhất trước (UC05). */
    List<BorrowingRecord> findByUserId(UUID userId);

    /** Số bản sao người dùng đang mượn và chưa trả. */
    long countActiveItemsByUserId(UUID userId);

    /** Số bản sao đang giữ quá hạn (QĐ2): chưa đóng lượt mượn mà phiếu đã quá hạn trả trước ngày today. */
    long countOverdueItemsByUserId(UUID userId, LocalDate today);

    /** Tìm phiếu theo điều kiện, phân trang ở DB, sắp xếp borrowDate giảm dần rồi id tăng dần; items nạp theo lô, không N+1. */
    BorrowingSearchResult search(BorrowingSearchCriteria criteria);
}
