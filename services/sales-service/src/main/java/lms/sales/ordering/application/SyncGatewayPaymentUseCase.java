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
                    .map(p -> processor.execute(tenantId, new ProcessPaymentWebhookUseCase.Command(current.reference(),
                            p.amount(), p.providerTxnId(), "PAID", p.paidAt()), current.provider()).intent())
                    .orElse(current);
        } catch (RuntimeException e) {
            // Cổng chậm/lỗi, số tiền lệch hoặc lệnh vừa bị hủy cùng lúc: không làm hỏng lần thăm dò, đọc lại ở lần sau
            log.warn("Không đồng bộ được thanh toán {} với cổng {}: {}", current.reference(), current.provider(),
                    e.getMessage());
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
                        processor.execute(tenantId, new ProcessPaymentWebhookUseCase.Command(intent.reference(),
                                p.amount(), p.providerTxnId(), "PAID", p.paidAt()), intent.provider());
                        return true;
                    }).orElse(false);
        } catch (RuntimeException e) {
            log.warn("Không kiểm được thanh toán {} với cổng {} trước khi đóng lệnh: {}", intent.reference(),
                    intent.provider(), e.getMessage());
            return false;
        }
    }
}
