package lms.sales.invoicing.application;

import java.util.UUID;

/**
 * Cổng móc từ ordering ở ba nơi đơn thành COMPLETED + PAID (hoàn tất đơn online, xác nhận thu tay POS, cổng thanh toán
 * POS): tự phát hành hóa đơn nếu tenant bật {@code autoIssueOnCompletion} và đơn có yêu cầu xuất hóa đơn đang mở. Mặc định
 * không làm gì. KHÔNG bao giờ ném lỗi nghiệp vụ (thiếu cấu hình hóa đơn chỉ bỏ qua và ghi log) để không làm hỏng việc
 * ghi nhận thanh toán.
 */
public interface InvoiceAutoIssuePort {

    InvoiceAutoIssuePort NONE = (tenantId, orderId, actor) -> { };

    void onOrderSettled(UUID tenantId, UUID orderId, UUID actor);
}
