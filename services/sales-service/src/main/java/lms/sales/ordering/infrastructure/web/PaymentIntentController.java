package lms.sales.ordering.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.SyncGatewayPaymentUseCase;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lệnh thanh toán QR cho FE (ADR-0020). Khóa quyền theo quy ước: {@code PaymentIntentController/get},
 * {@code /cancel}, {@code /confirm}, {@code /simulatePaid}. {@code simulatePaid} chỉ cho lệnh SIMULATED (MANUAL thì 409 PAYMENT_NOT_SIMULATED) và chỉ có khi sandbox bật (tắt thì 404
 * PAYMENT_SANDBOX_DISABLED); nó tự dựng thân webhook có ký rồi đi đúng luồng xử lý webhook.
 */
@RestController
@RequestMapping("/internal/payments/intents")
@RequiresModule(TenantModule.SALES)
public class PaymentIntentController {

    public record ConfirmPaymentRequest(@Min(0) Long tenderedAmount, @Size(max = 64) String transactionRef) {
    }

    private final PaymentIntentUseCases intents;
    private final SimulatedWebhookProcessor webhook;
    private final SyncGatewayPaymentUseCase gatewaySync;

    public PaymentIntentController(PaymentIntentUseCases intents, SimulatedWebhookProcessor webhook,
                                   SyncGatewayPaymentUseCase gatewaySync) {
        this.intents = intents;
        this.webhook = webhook;
        this.gatewaySync = gatewaySync;
    }

    @GetMapping("/{id}")
    public PaymentIntentResponse get(@PathVariable UUID id) {
        // Lệnh của cổng không có webhook (MoMo) được hỏi cổng ngay ở lần thăm dò này
        return gatewaySync.refresh(RequestSupport.tenant(), intents.get(RequestSupport.tenant(), id));
    }

    @PostMapping("/{id}/cancel")
    public PaymentIntentResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return intents.cancel(RequestSupport.tenant(), RequestSupport.user(jwt), id);
    }

    /**
     * Thu ngân xác nhận đã thu tiền (lệnh MANUAL, ADR-0021). Thân tùy chọn: {@code tenderedAmount} chỉ tiền mặt (vắng thì
     * bằng đúng số tiền), {@code transactionRef} bắt buộc với thẻ/chuyển khoản. Xác nhận lại lệnh đã PAID trả 200.
     */
    @PostMapping("/{id}/confirm")
    public PaymentIntentResponse confirm(@PathVariable UUID id,
                                         @Valid @RequestBody(required = false) ConfirmPaymentRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        var cmd = body == null ? new PaymentIntentUseCases.ConfirmCommand(null, null)
                : new PaymentIntentUseCases.ConfirmCommand(body.tenderedAmount(), body.transactionRef());
        return intents.confirm(RequestSupport.tenant(), RequestSupport.user(jwt), id, cmd);
    }

    @PostMapping("/{id}/simulate-paid")
    public PaymentIntentResponse simulatePaid(@PathVariable UUID id) {
        webhook.requireEnabled();
        PaymentIntentResponse intent = intents.get(RequestSupport.tenant(), id);
        String body = "{\"reference\":\"" + intent.reference() + "\",\"amount\":" + intent.amount()
                + ",\"providerTxnId\":\"SIM-" + UUID.randomUUID() + "\",\"status\":\"PAID\"}";
        return webhook.process(body, webhook.sign(body), PaymentAnomalySource.SIMULATE_BUTTON, null).intent();
    }
}
