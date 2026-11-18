package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceKind;
import lms.sales.invoicing.domain.InvoiceStatus;
import lms.sales.shared.application.PageResult;

/**
 * Lưu hóa đơn. {@code insert} chỉ cho hóa đơn mới (kể cả đã ISSUED trong cùng transaction); {@code update} chỉ ghi phần
 * cho phép đổi sau phát hành (hủy, cột tích hợp). Vi phạm chỉ mục duy nhất "một hóa đơn còn hiệu lực mỗi đơn" thì
 * ném INVOICE_ALREADY_EXISTS.
 */
public interface InvoiceRepository {

    Invoice insert(Invoice invoice);

    Invoice update(Invoice invoice);

    Optional<Invoice> findById(UUID tenantId, UUID id);

    Optional<Invoice> findByClientRequestId(UUID tenantId, UUID clientRequestId);

    /** Hóa đơn gốc/thay thế còn hiệu lực (ISSUED) của đơn. */
    Optional<Invoice> findEffectiveForOrder(UUID tenantId, UUID orderId);

    /** Hóa đơn điều chỉnh còn hiệu lực của một hóa đơn gốc, theo thứ tự tạo (kèm dòng, nạp theo lô). */
    List<Invoice> findActiveAdjustments(UUID tenantId, UUID originalInvoiceId);

    Optional<Invoice> findAdjustmentByReturn(UUID tenantId, UUID returnId);

    PageResult<InvoiceSummary> search(UUID tenantId, Criteria criteria, int page, int size);

    /** {@code from} bao gồm, {@code to} loại trừ, theo thời điểm tạo; {@code keyword} khớp số đơn, tên/đơn vị/MST người mua, số hóa đơn. */
    record Criteria(InvoiceStatus status, InvoiceKind kind, Instant from, Instant to, String keyword) {
    }
}
