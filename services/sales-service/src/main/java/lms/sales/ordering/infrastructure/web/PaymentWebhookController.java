package lms.sales.ordering.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.security.permission.PublicEndpoint;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook của cổng thanh toán GIẢ LẬP (ADR-0020). Công khai ở lớp xác thực JWT (cổng không có token): danh tính duy nhất
 * là chữ ký HMAC-SHA256 trong header {@code X-Signature}, kiểm trong {@link SimulatedWebhookProcessor}. Không đi qua lms-api.
 * Thân JSON: {@code {reference, amount, providerTxnId, status}}; status PAID (hoặc SUCCESS) là đã nhận tiền.
 */
@RestController
@RequestMapping("/internal/payments/webhook")
@RequiresModule(TenantModule.SALES)
public class PaymentWebhookController {

    private final SimulatedWebhookProcessor processor;

    public PaymentWebhookController(SimulatedWebhookProcessor processor) {
        this.processor = processor;
    }

    @PublicEndpoint
    @PostMapping(value = "/simulated", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> simulated(@RequestBody String body,
                                         @RequestHeader(value = SimulatedWebhookProcessor.SIGNATURE_HEADER,
                                                 required = false) String signature,
                                         HttpServletRequest request) {
        var result = processor.process(body, signature, PaymentAnomalySource.WEBHOOK, request.getRemoteAddr());
        String outcome = result.ignored() ? "IGNORED" : result.duplicate() ? "ALREADY_PROCESSED" : "PROCESSED";
        return Map.of("result", outcome, "reference", result.intent().reference(),
                "intentStatus", result.intent().status().name());
    }
}
