package lms.sales.ordering.application;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Đồng bộ kết quả thanh toán với cổng không đẩy được webhook về máy này (MoMo sandbox): khi FE thăm dò một lệnh còn
 * PENDING thuộc cổng đó, hỏi cổng; cổng báo đã thanh toán thì ghi nhận bằng đúng luồng của webhook (idempotent theo mã
 * giao dịch, kiểm số tiền). Không transactional: lời gọi HTTP ra ngoài không được giữ transaction mở; phần ghi nằm trong
 * {@link ProcessPaymentWebhookUseCase} (qua {@link ApplyGatewayPaymentUseCase}, nơi phân loại bất thường, ADR-0029). Cổng lỗi hoặc không trả lời thì giữ nguyên trạng thái (FE thăm dò lại sau).
 */
public class SyncGatewayPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncGatewayPaymentUseCase.class);

    private final List<PaymentGatewayPort> gateways;
    private final ApplyGatewayPaymentUseCase apply;

    public SyncGatewayPaymentUseCase(List<PaymentGatewayPort> gateways, ApplyGatewayPaymentUseCase apply) {
        this.gateways = gateways;
        this.apply = apply;
    }

    /** Trả lệnh mới nhất: đã PAID nếu cổng vừa báo thanh toán, ngược lại trả lại {@code current}. */
    public PaymentIntentResponse refresh(UUID tenantId, PaymentIntentResponse current) {
        if (current.status() != PaymentIntentStatus.PENDING) {
            return current;
        }
        PaymentGatewayPort gateway = gateways.stream().filter(g -> g.provider().equals(current.provider()))
                .findFirst().orElse(null);
        if (gateway == null) {
            return current;
        }
        try {
            return gateway.queryPayment(current.reference(), current.amount(), current.createdAt())
                    .filter(PaymentGatewayPort.GatewayPayment::paid)
                    .map(p -> {
                        PaymentIntentResponse paid = apply.apply(tenantId, new ProcessPaymentWebhookUseCase.Command(
                                current.reference(), p.amount(), p.providerTxnId(), "PAID", p.paidAt()),
                                current.provider(), PaymentAnomalySource.POLL).intent();
                        log.info("Đã ghi nhận thanh toán qua thăm dò intentId={} provider={} status={}", paid.id(),
                                paid.provider(), paid.status());
                        return paid;
                    })
                    .orElse(current);
        } catch (RuntimeException e) {
            // Cổng chậm/lỗi, số tiền lệch hoặc lệnh vừa bị hủy cùng lúc: không làm hỏng lần thăm dò, đọc lại ở lần sau.
            // Lỗi cổng đã được kênh OUTBOUND ghi (có errorKind), bất thường tiền đã do ApplyGatewayPaymentUseCase ghi;
            // KHÔNG log getMessage(): thông điệp lỗi HTTP có thể chứa URL kèm mã tham chiếu (ADR-0029).
            if (log.isDebugEnabled()) {
                log.debug("Thăm dò thanh toán chưa ghi nhận intentId={} provider={} errorClass={}", current.id(),
                        current.provider(), e.getClass().getSimpleName());
            }
            return current;
        }
    }

    /**
     * Hỏi cổng lần cuối trước khi hủy/hết hạn một lệnh (ADR-0029 mục 2.3), best-effort: cổng báo đã nhận tiền thì ghi
     * nhận như webhook và trả true (người gọi KHÔNG hủy nữa); cổng lỗi, hết hạn mức, lệnh không thuộc cổng nào hoặc
     * không thấy tiền thì trả false. Lệnh quá hạn theo đồng hồ vẫn được nhận nếu giao dịch xảy ra trước hạn. Dùng
     * kênh "cuối" của cổng (bỏ qua giới hạn theo lệnh của bộ hãm). Chỉ lệnh còn PENDING trong DB.
     */
    public boolean settleBeforeClosing(UUID tenantId, PaymentIntent intent) {
        if (intent.status() != PaymentIntentStatus.PENDING) {
            return false;
        }
        PaymentGatewayPort gateway = gateways.stream().filter(g -> g.provider().equals(intent.provider()))
                .findFirst().orElse(null);
        if (gateway == null) {
            return false;
        }
        try {
            return gateway.queryPaymentFinal(intent.reference(), intent.amount().amount(), intent.createdAt())
                    .filter(PaymentGatewayPort.GatewayPayment::paid)
                    .map(p -> {
                        apply.apply(tenantId, new ProcessPaymentWebhookUseCase.Command(intent.reference(),
                                p.amount(), p.providerTxnId(), "PAID", p.paidAt()), intent.provider(),
                                PaymentAnomalySource.POLL);
                        return true;
                    }).orElse(false);
        } catch (RuntimeException e) {
            // Không log getMessage() (có thể chứa URL kèm mã tham chiếu): chỉ tên lớp lỗi (ADR-0029)
            log.warn("Không kiểm được thanh toán intentId={} với cổng {} trước khi đóng lệnh: errorClass={}",
                    intent.id(), intent.provider(), e.getClass().getSimpleName());
            return false;
        }
    }
}
