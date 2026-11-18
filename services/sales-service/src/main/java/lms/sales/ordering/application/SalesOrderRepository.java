package lms.sales.ordering.application;

import java.time.Instant;
import java.util.List;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.SalesOrder;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;

/**
 * {@code save} (đơn mới): trùng (tenant, clientRequestId) thì ném DuplicateClientRequestException.
 * Adapter đọc/ghi mọi trường của aggregate gồm dòng và thanh toán.
 */
public interface SalesOrderRepository {

    SalesOrder save(SalesOrder order);

    Optional<SalesOrder> findById(UUID tenantId, UUID id);

    Optional<SalesOrder> findByClientRequestId(UUID tenantId, UUID clientRequestId);

    /** Đơn ONLINE của đúng khách này (tenant, chủ và kênh đều nằm trong truy vấn); đơn người khác/POS thì rỗng. */
    Optional<SalesOrder> findByIdAndCustomer(UUID tenantId, UUID id, UUID customerUserId);

    /**
     * Khóa ghi (PESSIMISTIC_WRITE) các dòng của đơn đến hết transaction: tuần tự hóa các phiếu trả đồng thời trên
     * cùng đơn trước khi tính "đã mua trừ đã trả".
     */
    void lockLines(UUID tenantId, UUID orderId);

    /**
     * Id đơn online chờ xác nhận đã quá hạn giữ hàng (reservation_expires_at <= now), cũ nhất trước, tối đa
     * {@code limit}. Chỉ đọc id: từng đơn được khóa và xử lý riêng bằng {@link #findByIdSkipLocked}.
     */
    List<UUID> findExpiredReservationIds(UUID tenantId, Instant now, int limit);

    /**
     * Đọc đơn kèm khóa ghi (FOR UPDATE SKIP LOCKED): đơn đang bị giao dịch khác khóa thì trả rỗng, để nhiều worker chạy
     * đồng thời không xử lý trùng một đơn. Khóa giữ đến hết transaction.
     */
    Optional<SalesOrder> findByIdSkipLocked(UUID tenantId, UUID id);

    /** Số đơn ONLINE của khách đang chờ: PENDING_CONFIRMATION hoặc AWAITING_PAYMENT (chờ thanh toán, ADR-0029). */
    long countPendingOnline(UUID tenantId, UUID customerUserId);

    /** Số đơn ONLINE khách đã đặt từ {@code since} (kể cả đã hủy: hủy rồi đặt lại vẫn tính). */
    long countOnlineSince(UUID tenantId, UUID customerUserId, Instant since);

    /** Số đơn ONLINE của khách đã bị hủy với createdAt từ {@code since}. */
    long countCancelledOnlineSince(UUID tenantId, UUID customerUserId, Instant since);

    PageResult<SalesOrder> search(UUID tenantId, Criteria criteria, int page, int size);

    /** keyword khớp số đơn; from/to lọc theo createdAt (from bao gồm, to loại trừ). */
    record Criteria(OrderStatus status, PaymentStatus paymentStatus, OrderChannel channel, Instant from,
                    Instant to, String keyword, UUID customerUserId) {
        public Criteria(OrderStatus status, PaymentStatus paymentStatus, OrderChannel channel, Instant from,
                        Instant to, String keyword) {
            this(status, paymentStatus, channel, from, to, keyword, null);
        }
    }
}
