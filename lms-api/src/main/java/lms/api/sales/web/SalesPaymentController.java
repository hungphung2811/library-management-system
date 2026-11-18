package lms.api.sales.web;

import java.util.UUID;
import lms.api.sales.client.SalesPaymentClient;
import jakarta.validation.Valid;
import lms.api.sales.dto.SalesDtos.ConfirmPaymentRequest;
import lms.api.sales.dto.SalesDtos.PaymentIntentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lệnh thanh toán QR của POS (ADR-0020): FE thăm dò trạng thái, hủy, xác nhận thu tay (MANUAL) và bấm "giả lập đã thanh toán" (chỉ khi sandbox
 * bật ở sales-service, tắt thì 404 PAYMENT_SANDBOX_DISABLED). Chuyển nguyên sang /internal/payments/intents/**
 * (kiểm khóa, module, tenant ở sales); lms-api chỉ xác thực, lỗi 4xx chuyển nguyên.
 */
@RestController
@RequestMapping("/api/sales/payments/intents")
public class SalesPaymentController {

    private final SalesPaymentClient payments;

    public SalesPaymentController(SalesPaymentClient payments) {
        this.payments = payments;
    }

    @GetMapping("/{id}")
    public PaymentIntentView get(@PathVariable UUID id) {
        return payments.getIntent(id);
    }

    @PostMapping("/{id}/cancel")
    public PaymentIntentView cancel(@PathVariable UUID id) {
        return payments.cancelIntent(id);
    }

    /** Thu ngân xác nhận đã thu (lệnh MANUAL). Thân tùy chọn; xác nhận lại lệnh đã thu trả 200 nguyên trạng. */
    @PostMapping("/{id}/confirm")
    public PaymentIntentView confirm(@PathVariable UUID id,
                                     @Valid @RequestBody(required = false) ConfirmPaymentRequest body) {
        return payments.confirmIntent(id, body);
    }

    @PostMapping("/{id}/simulate-paid")
    public PaymentIntentView simulatePaid(@PathVariable UUID id) {
        return payments.simulatePaid(id);
    }
}
