package lms.circulation.fine.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu đề nghị hoàn khoản của tenant hiện tại. */
public interface FineRefundRepository {

    /** Lưu mới hoặc cập nhật quyết định. Ném FineRuleException REFUND_ALREADY_EXISTS nếu khoản đã có đề nghị chờ/đã duyệt. */
    FineRefund save(FineRefund refund);

    Optional<FineRefund> findByIdForUpdate(UUID id);

    /** Có đề nghị đang chờ hoặc đã duyệt cho khoản này (mỗi khoản mất chỉ hoàn một lần). */
    boolean existsActiveForFine(UUID fineId);

    /** Danh sách có lọc (status, readerId tùy chọn), mới nhất trước, phân trang ở DB. Một truy vấn, không N+1. */
    Page search(Criteria criteria);

    record Criteria(ApprovalStatus status, UUID readerId, int page, int size) {
    }

    record Page(List<FineRefund> items, long total) {
    }
}
