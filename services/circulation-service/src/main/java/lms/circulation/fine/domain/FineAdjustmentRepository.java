package lms.circulation.fine.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu đề nghị miễn/giảm của tenant hiện tại. */
public interface FineAdjustmentRepository {

    /** Lưu mới hoặc cập nhật quyết định. Ném FineRuleException ADJUSTMENT_ALREADY_PENDING nếu khoản đã có đề nghị chờ. */
    FineAdjustmentRequest save(FineAdjustmentRequest request);

    Optional<FineAdjustmentRequest> findByIdForUpdate(UUID id);

    boolean existsPendingForFine(UUID fineId);

    /** Danh sách có lọc (status, fineId, readerId đều tùy chọn), mới nhất trước, phân trang ở DB. Một truy vấn, không N+1. */
    Page search(Criteria criteria);

    record Criteria(ApprovalStatus status, UUID fineId, UUID readerId, int page, int size) {
    }

    record Page(List<FineAdjustmentRequest> items, long total) {
    }
}
