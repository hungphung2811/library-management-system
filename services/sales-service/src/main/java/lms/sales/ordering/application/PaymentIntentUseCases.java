package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.StockService;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra cứu, hủy và hết hạn lệnh thanh toán QR (ADR-0020). Hủy/hết hạn: lệnh sang CANCELLED/EXPIRED, đơn sang CANCELLED
 * và tồn đang giữ được nhả, cùng một transaction của sales_db. Hai tiến trình tranh nhau (webhook đã thu và thu ngân
 * hủy) đụng khóa lạc quan của lệnh: bên thua nhận 409 và rollback.
 */
public class PaymentIntentUseCases {

    static final String CANCEL_REASON = "Hủy thanh toán QR";
    static final String EXPIRE_REASON = "Hết hạn thanh toán QR";

    private final PaymentIntentRepository intents;
    private final SalesOrderRepository orders;
    private final StockService stockService;
    private final SalesAuditPort audit;
    private final Clock clock;

    public PaymentIntentUseCases(PaymentIntentRepository intents, SalesOrderRepository orders,
                                 StockService stockService, SalesAuditPort audit, Clock clock) {
        this.intents = intents;
        this.orders = orders;
        this.stockService = stockService;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PaymentIntentResponse get(UUID tenantId, UUID intentId) {
        return PaymentIntentResponse.from(load(tenantId, intentId), clock.instant());
    }

    /** Thu ngân hủy lệnh còn PENDING. Hủy lại lệnh đã hủy thì trả nguyên trạng (idempotent); trạng thái khác thì 409. */
    @Transactional
    public PaymentIntentResponse cancel(UUID tenantId, UUID actor, UUID intentId) {
        return cancel(tenantId, actor, intentId, null);
    }

    /** Như trên với lý do hủy của người gọi (khách hủy đơn online chờ thanh toán, ADR-0029); null/trống thì dùng lý do mặc định. */
    @Transactional
    public PaymentIntentResponse cancel(UUID tenantId, UUID actor, UUID intentId, String reason) {
        PaymentIntent intent = load(tenantId, intentId);
        Instant now = clock.instant();
        if (intent.status() == PaymentIntentStatus.CANCELLED) {
            return PaymentIntentResponse.from(intent, now);
        }
        intent.cancel();
        release(tenantId, actor, intent, reason == null || reason.isBlank() ? CANCEL_REASON : reason, now,
                "CANCELLED");
        return PaymentIntentResponse.from(intents.save(intent), now);
    }

    /** Nội dung xác nhận thu tay: tenderedAmount chỉ CASH; transactionRef bắt buộc với CARD/BANK_TRANSFER. */
    public record ConfirmCommand(Long tenderedAmount, String transactionRef) {
    }

    /**
     * Thu ngân xác nhận đã thu tiền (lệnh MANUAL còn hạn, ADR-0021): đơn COMPLETED + PAID kèm bản ghi thu theo phương thức
     * thật (người ghi = người bấm, reference = transactionRef hoặc mã lệnh), tồn giữ chuyển thành xuất thật. Xác nhận lại
     * lệnh đã PAID thì trả nguyên trạng (200). Lệnh QR/SIMULATED thì 409 PAYMENT_NOT_MANUAL; hết hạn/hủy thì 409
     * PAYMENT_INTENT_STATE_CONFLICT. Cùng khóa lạc quan với webhook/hủy nên hai người bấm cùng lúc chỉ một bên thắng.
     */
    @Transactional
    public PaymentIntentResponse confirm(UUID tenantId, UUID actor, UUID intentId, ConfirmCommand cmd) {
        PaymentIntent intent = load(tenantId, intentId);
        Instant now = clock.instant();
        if (!PaymentIntent.PROVIDER_MANUAL.equals(intent.provider())) {
            throw new SalesException(ErrorCode.PAYMENT_NOT_MANUAL, "Lệnh thanh toán này không xác nhận thủ công");
        }
        if (intent.status() == PaymentIntentStatus.PAID) {
            return PaymentIntentResponse.from(intent, now);
        }
        SalesOrder order = orders.findById(tenantId, intent.orderId())
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        Long tendered = cmd == null ? null : cmd.tenderedAmount();
        String ref = cmd == null ? null : cmd.transactionRef();
        intent.confirmManual(order.paymentMethod(), ref, tendered, now);
        var record = order.settleAwaitingPayment(intent.transactionRef() != null ? intent.transactionRef()
                : intent.reference(), actor, now);
        PaymentIntent savedIntent = intents.save(intent);
        SalesOrder saved = orders.save(order);
        stockService.fulfilAll(tenantId, actor, OrderWorkflowUseCases.stockLines(saved), "ORDER", saved.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", saved.orderNo());
        data.put("status", saved.status().name());
        data.put("paymentStatus", saved.paymentStatus().name());
        data.put("amount", record.amount().amount());
        data.put("method", record.method().name());
        data.put("paymentIntentId", savedIntent.id().toString());
        data.put("provider", savedIntent.provider());
        if (savedIntent.changeDue() != null) {
            data.put("changeDue", savedIntent.changeDue());
        }
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.ORDER_PAID, "SalesOrder",
                saved.id(), actor, now, data));
        return PaymentIntentResponse.from(savedIntent, now);
    }

    /** Dọn một lệnh đã quá hạn (job gọi). Chưa quá hạn hoặc đã xử lý rồi thì không làm gì; trả true khi đã hết hạn. */
    @Transactional
    public boolean expireOne(UUID tenantId, UUID intentId) {
        PaymentIntent intent = load(tenantId, intentId);
        Instant now = clock.instant();
        if (!intent.isExpiredAt(now)) {
            return false;
        }
        intent.expire(now);
        release(tenantId, null, intent, EXPIRE_REASON, now, "EXPIRED");
        intents.save(intent);
        return true;
    }

    private void release(UUID tenantId, UUID actor, PaymentIntent intent, String reason, Instant now,
                         String intentOutcome) {
        SalesOrder order = orders.findById(tenantId, intent.orderId())
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        order.cancelAwaitingPayment(reason, actor, now);
        SalesOrder saved = orders.save(order);
        stockService.releaseAll(tenantId, actor, OrderWorkflowUseCases.stockLines(saved), "ORDER", saved.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", saved.orderNo());
        data.put("channel", saved.channel().name());
        data.put("status", saved.status().name());
        data.put("paymentStatus", saved.paymentStatus().name());
        data.put("stockEffect", "RELEASE");
        data.put("paymentIntentId", intent.id().toString());
        data.put("paymentIntent", intentOutcome);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.ORDER_CANCELLED, "SalesOrder",
                saved.id(), actor, now, data));
    }

    private PaymentIntent load(UUID tenantId, UUID intentId) {
        return intents.findById(tenantId, intentId).orElseThrow(
                () -> new SalesException(ErrorCode.PAYMENT_INTENT_NOT_FOUND, "Không có lệnh thanh toán"));
    }
}
