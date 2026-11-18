package lms.api.sales.client;

import java.util.UUID;
import lms.api.sales.dto.SalesDtos.ConfirmPaymentRequest;
import lms.api.sales.dto.SalesDtos.PaymentIntentView;

/**
 * Cổng sang /internal/payments/intents/** của sales-service (thanh toán QR của POS, ADR-0020). Webhook của cổng thanh
 * toán KHÔNG đi qua lms-api (công khai ở sales-service, chỉ xác thực bằng chữ ký).
 */
public interface SalesPaymentClient {

    PaymentIntentView getIntent(UUID id);

    PaymentIntentView cancelIntent(UUID id);

    /** Thu ngân xác nhận đã thu tiền; body có thể null (tiền mặt, đúng số tiền). */
    PaymentIntentView confirmIntent(UUID id, ConfirmPaymentRequest request);

    PaymentIntentView simulatePaid(UUID id);
}
