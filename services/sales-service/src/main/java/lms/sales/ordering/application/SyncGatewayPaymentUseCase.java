package lms.sales.ordering.application;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Đồng bộ kết quả thanh toán với cổng không đẩy được webhook về máy này (MoMo sandbox): khi FE thăm dò một lệnh còn
 * PENDING thuộc cổng đó, hỏi cổng; cổng báo đã thanh toán thì ghi nhận bằng đúng luồng của webhook (idempotent theo mã
 * giao dịch, kiểm số tiền). Không transactional: lời gọi HTTP ra ngoài không được giữ transaction mở; phần ghi nằm trong
 * {@link ProcessPaymentWebhookUseCase}. Cổng lỗi hoặc không trả lời thì giữ nguyên trạng thái (FE thăm dò lại sau).
 */
public class SyncGatewayPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncGatewayPaymentUseCase.class);

    private final List<PaymentGatewayPort> gateways;
    private final ProcessPaymentWebhookUseCase processor;

    public SyncGatewayPaymentUseCase(List<PaymentGatewayPort> gateways, ProcessPaymentWebhookUseCase processor) {
        this.gateways = gateways;
        this.processor = processor;
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
            return gateway.queryPayment(current.reference())
                    .filter(PaymentGatewayPort.GatewayPayment::paid)
                    .map(p -> processor.execute(tenantId, new ProcessPaymentWebhookUseCase.Command(current.reference(),
                            p.amount(), p.providerTxnId(), "PAID"), current.provider()).intent())
                    .orElse(current);
        } catch (RuntimeException e) {
            // Cổng chậm/lỗi, số tiền lệch hoặc lệnh vừa bị hủy cùng lúc: không làm hỏng lần thăm dò, đọc lại ở lần sau
            log.warn("Không đồng bộ được thanh toán {} với cổng {}: {}", current.reference(), current.provider(),
                    e.getMessage());
            return current;
        }
    }
}
