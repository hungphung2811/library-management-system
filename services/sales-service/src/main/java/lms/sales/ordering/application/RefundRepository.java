package lms.sales.ordering.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.shared.application.PageResult;

/**
 * Lưu khoản hoàn. {@code save} đơn mới thì chèn; khoản đã có thì cập nhật có kiểm version (lệch version là
 * SalesException ORDER_STATE_CONFLICT).
 */
public interface RefundRepository {

    Refund save(Refund refund);

    Optional<Refund> findById(UUID tenantId, UUID id);

    PageResult<Refund> search(UUID tenantId, RefundStatus status, int page, int size);

    List<Refund> findByOrderId(UUID tenantId, UUID orderId);
}
