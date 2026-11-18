package lms.sales.invoicing.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Cổng đọc đơn cho hóa đơn. Cài đặt nằm ở context ordering (ordering dùng tầng application của invoicing, không
 * ngược lại, để không có vòng phụ thuộc).
 */
public interface InvoiceOrderSourcePort {

    /** Đơn của tenant (nhân viên). */
    Optional<InvoiceOrder> find(UUID tenantId, UUID orderId);

    /** Đơn ONLINE của đúng khách này; đơn người khác, đơn POS hoặc không tồn tại thì rỗng (cùng một kết quả). */
    Optional<InvoiceOrder> findOwnedOnline(UUID tenantId, UUID orderId, UUID customerUserId);

    /** Khóa ghi đơn đến hết transaction: tuần tự hóa phát hành/hủy/thay thế/điều chỉnh trên cùng đơn. */
    void lock(UUID tenantId, UUID orderId);
}
