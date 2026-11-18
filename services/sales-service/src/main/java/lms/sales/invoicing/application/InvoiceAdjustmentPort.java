package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cổng móc từ ordering: khi phiếu trả hàng được lập, tự sinh hóa đơn điều chỉnh giảm nếu đơn có hóa đơn còn hiệu lực
 * (cùng transaction). Đơn không có hóa đơn thì không làm gì, nên hành vi trả hàng hiện có không đổi.
 */
public interface InvoiceAdjustmentPort {

    /** Không làm gì (dùng khi chưa nối context hóa đơn, ví dụ test cũ). */
    InvoiceAdjustmentPort NONE = (tenantId, orderId, returnId, lines, actor, now) -> { };

    /** {@code refundAmount}: tiền hoàn của dòng trả (đồng); dòng 0 đồng bị bỏ qua. */
    record ReturnedLine(UUID orderLineId, int quantity, long refundAmount) {
    }

    void adjustForReturn(UUID tenantId, UUID orderId, UUID returnId, List<ReturnedLine> lines, UUID actor,
                         Instant now);
}
