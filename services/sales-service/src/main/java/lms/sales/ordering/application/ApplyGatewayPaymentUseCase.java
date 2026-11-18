package lms.sales.ordering.application;

import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Ghi nhận thanh toán do cổng báo và phân loại bất thường (ADR-0029 D5). KHÔNG transactional, cố ý: bọc lời gọi
 * {@link ProcessPaymentWebhookUseCase} (transactional) để khi nó ném {@link SalesException} thì transaction của nó đã
 * rollback và trả kết nối; lúc đó mới phân loại, ghi bất thường (log + audit ở transaction riêng) rồi ném lại đúng
 * exception gốc (hợp đồng HTTP không đổi). Người gọi cũng không được giữ transaction mở khi gọi vào đây, nếu không audit
 * bất thường sẽ không thoát được rollback (REQUIRES_NEW vẫn độc lập, nhưng lệnh đọc lại có thể thấy dữ liệu cũ).
 * Lỗi trong chính việc phân loại/ghi không bao giờ che exception gốc.
 */
public class ApplyGatewayPaymentUseCase {

    private final ProcessPaymentWebhookUseCase processor;
    private final PaymentIntentRepository intents;
    private final RecordPaymentAnomalyUseCase recorder;
    private final PaymentAnomalyLogPort logPort;

    public ApplyGatewayPaymentUseCase(ProcessPaymentWebhookUseCase processor, PaymentIntentRepository intents,
                                      RecordPaymentAnomalyUseCase recorder, PaymentAnomalyLogPort logPort) {
        this.processor = processor;
        this.intents = intents;
        this.recorder = recorder;
        this.logPort = logPort;
    }

    public ProcessPaymentWebhookUseCase.Result apply(UUID tenantId, ProcessPaymentWebhookUseCase.Command cmd,
                                                     String provider, PaymentAnomalySource source) {
        try {
            return processor.execute(tenantId, cmd, provider);
        } catch (SalesException e) {
            classifyQuietly(tenantId, cmd, provider, source, e);
            throw e;
        }
    }

    private void classifyQuietly(UUID tenantId, ProcessPaymentWebhookUseCase.Command cmd, String provider,
                                 PaymentAnomalySource source, SalesException e) {
        try {
            PaymentIntent intent = needsIntent(e.code())
                    ? intents.findByReference(tenantId, cmd.reference()).orElse(null) : null;
            PaymentAnomalyType type = typeOf(e.code(), intent, cmd);
            if (type != null) {
                recorder.report(PaymentAnomaly.of(type, source, provider).tenantId(tenantId)
                        .intentId(intent == null ? null : intent.id()).orderId(intent == null ? null : intent.orderId())
                        .reference(cmd.reference()).expectedAmount(intent == null ? null : intent.amount().amount())
                        .receivedAmount(cmd.amount()).providerTxnId(cmd.providerTxnId())
                        .intentStatus(intent == null ? null : intent.status().name()).errorCode(e.code().name())
                        .build());
            } else if (e.code() == ErrorCode.PAYMENT_INTENT_STATE_CONFLICT) {
                logPort.conflict(source, provider, tenantId, intent == null ? null : intent.id(), cmd.reference());
            }
        } catch (RuntimeException ignored) {
            // Phân loại hỏng không được che exception gốc; RecordPaymentAnomalyUseCase đã tự log lỗi của nó
        }
    }

    private static boolean needsIntent(ErrorCode code) {
        return switch (code) {
            case PAYMENT_AMOUNT_MISMATCH, PAYMENT_INTENT_EXPIRED, PAYMENT_INTENT_CANCELLED,
                 PAYMENT_DUPLICATE_TRANSACTION, PAYMENT_NOT_SIMULATED, PAYMENT_INTENT_STATE_CONFLICT -> true;
            default -> false;
        };
    }

    /** Null nếu lỗi không phải bất thường (vd xung đột phiên bản, thiếu dữ liệu, không tìm thấy). */
    private static PaymentAnomalyType typeOf(ErrorCode code, PaymentIntent intent,
                                             ProcessPaymentWebhookUseCase.Command cmd) {
        return switch (code) {
            case PAYMENT_AMOUNT_MISMATCH -> PaymentAnomalyType.PAYMENT_AMOUNT_MISMATCH;
            case PAYMENT_INTENT_EXPIRED -> PaymentAnomalyType.PAYMENT_AFTER_EXPIRY;
            case PAYMENT_INTENT_CANCELLED -> PaymentAnomalyType.PAYMENT_AFTER_CANCEL;
            case PAYMENT_DUPLICATE_TRANSACTION -> PaymentAnomalyType.PAYMENT_TXN_REUSED_OTHER_INTENT;
            case PAYMENT_NOT_SIMULATED -> PaymentAnomalyType.PAYMENT_PROVIDER_MISMATCH;
            case PAYMENT_INTENT_STATE_CONFLICT -> paidByOtherTransaction(intent, cmd)
                    ? PaymentAnomalyType.PAYMENT_DOUBLE_PAID : null;
            default -> null;
        };
    }

    private static boolean paidByOtherTransaction(PaymentIntent intent, ProcessPaymentWebhookUseCase.Command cmd) {
        return intent != null && intent.status() == PaymentIntentStatus.PAID && cmd.providerTxnId() != null
                && !cmd.providerTxnId().trim().equals(intent.providerTxnId());
    }
}
