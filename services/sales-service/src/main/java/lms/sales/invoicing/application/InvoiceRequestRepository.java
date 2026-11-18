package lms.sales.invoicing.application;

import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceRequest;

/** Yêu cầu xuất hóa đơn, mỗi đơn tối đa một ((tenant, order) duy nhất). */
public interface InvoiceRequestRepository {

    Optional<InvoiceRequest> findByOrder(UUID tenantId, UUID orderId);

    /** Chèn mới hoặc cập nhật (khóa lạc quan theo version); hai bên tạo cùng lúc thì bên thua nhận 409. */
    InvoiceRequest save(InvoiceRequest request);
}
