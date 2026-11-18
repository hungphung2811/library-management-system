package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentRecord;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.StockService;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xử lý kết quả thanh toán do cổng báo (ADR-0020). Người gọi đã xác thực chữ ký và đặt TenantContext theo tenant của
 * lệnh trước khi vào đây (transaction mở sau đó nên RLS đúng tenant). Idempotent theo mã giao dịch: cùng
 * {@code providerTxnId} gửi lại trả kết quả cũ, không thu hai lần. Lệnh hết hạn/hủy bị từ chối bằng mã lỗi riêng; số tiền
 * lệch thì 400 và không đổi gì. Thành công: lệnh PAID, đơn COMPLETED + PAID kèm bản ghi thu (QR, mã tham chiếu), tồn
 * giữ chuyển thành xuất thật (FULFIL).
 */
public class ProcessPaymentWebhookUseCase {

    /** {@code status}: PAID (hoặc SUCCESS) là đã nhận tiền; giá trị khác (vd FAILED) chỉ được ghi nhận, không đổi gì. */
    public record Command(String reference, long amount, String providerTxnId, String status) {
    }

    public record Result(PaymentIntentResponse intent, boolean duplicate, boolean ignored) {
    }

    private final PaymentIntentRepository intents;
    private final SalesOrderRepository orders;
    private final StockService stockService;
    private final SalesAuditPort audit;
    private final Clock clock;

    public ProcessPaymentWebhookUseCase(PaymentIntentRepository intents, SalesOrderRepository orders,
                                        StockService stockService, SalesAuditPort audit, Clock clock) {
        this.intents = intents;
        this.orders = orders;
        this.stockService = stockService;
        this.audit = audit;
        this.clock = clock;
    }

    /** Webhook của cổng giả lập (chỉ lệnh SIMULATED). */
    @Transactional
    public Result execute(UUID tenantId, Command cmd) {
        return execute(tenantId, cmd, PaymentIntent.PROVIDER_SIMULATED);
    }

    /** Ghi nhận thanh toán của cổng {@code provider}: lệnh phải thuộc đúng cổng đó. */
    @Transactional
    public Result execute(UUID tenantId, Command cmd, String provider) {
        PaymentIntent intent = intents.findByReference(tenantId, cmd.reference()).orElseThrow(
                () -> new SalesException(ErrorCode.PAYMENT_INTENT_NOT_FOUND, "Không có lệnh thanh toán"));
        Instant now = clock.instant();
        if (!provider.equals(intent.provider())) {
            // Lệnh thu tay (MANUAL) không do cổng này xác nhận, dù biết mã tham chiếu và có chữ ký hợp lệ
            throw new SalesException(ErrorCode.PAYMENT_NOT_SIMULATED, "Lệnh thanh toán này không thuộc cổng giả lập");
        }
        boolean success = "PAID".equalsIgnoreCase(cmd.status()) || "SUCCESS".equalsIgnoreCase(cmd.status());
        if (!success) {
            return new Result(PaymentIntentResponse.from(intent, now), false, true);
        }
        if (cmd.providerTxnId() == null || cmd.providerTxnId().isBlank()) {
            throw SalesException.invalidRequest("Thiếu mã giao dịch của cổng thanh toán");
        }
        if (intent.status() == PaymentIntentStatus.PAID) {
            if (cmd.providerTxnId().trim().equals(intent.providerTxnId())) {
                return new Result(PaymentIntentResponse.from(intent, now), true, false);
            }
            throw new SalesException(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                    "Lệnh thanh toán đã được thanh toán bằng giao dịch khác");
        }
        intent.markPaid(cmd.providerTxnId(), Money.of(cmd.amount()), now);

        SalesOrder order = orders.findById(tenantId, intent.orderId())
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        PaymentRecord record = order.settleQrPayment(intent.reference(), now);
        // Lưu lệnh trước đơn: xung đột phiên bản (webhook trùng đồng thời, hoặc hủy cùng lúc) báo sớm, rollback cả tồn
        PaymentIntent savedIntent = intents.save(intent);
        SalesOrder saved = orders.save(order);
        stockService.fulfilAll(tenantId, null, OrderWorkflowUseCases.stockLines(saved), "ORDER", saved.id());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", saved.orderNo());
        data.put("status", saved.status().name());
        data.put("paymentStatus", saved.paymentStatus().name());
        data.put("amount", record.amount().amount());
        data.put("method", record.method().name());
        data.put("reference", record.reference());
        data.put("paymentIntentId", savedIntent.id().toString());
        data.put("provider", savedIntent.provider());
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.ORDER_PAID, "SalesOrder",
                saved.id(), null, now, data));
        return new Result(PaymentIntentResponse.from(savedIntent, now), false, false);
    }
}
