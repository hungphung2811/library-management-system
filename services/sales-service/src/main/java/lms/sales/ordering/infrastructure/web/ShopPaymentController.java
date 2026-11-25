package lms.sales.ordering.infrastructure.web;

import java.util.UUID;
import lms.sales.ordering.application.ShopPaymentUseCases;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thanh toán trực tuyến của khách (ADR-0029), cùng đường dẫn gốc với {@link ShopController}. Cả hai endpoint chỉ đọc
 * dữ liệu của chính người gọi nên {@code @AuthenticatedOnly} (không khóa riêng, không đổi role Khách hàng hay allowlist
 * token storefront); vẫn đòi tenant và module SALES. Quyền sở hữu kiểm trong use case: đơn của người khác hoặc không
 * tồn tại cùng trả 404 ORDER_NOT_FOUND. Ghi (đặt đơn, hủy) dùng lại {@code ShopController/placeOrder} và
 * {@code ShopController/cancelMyOrder}.
 */
@RestController
@RequestMapping("/internal/sales/shop")
@RequiresModule(TenantModule.SALES)
public class ShopPaymentController {

    private final ShopPaymentUseCases payments;

    public ShopPaymentController(ShopPaymentUseCases payments) {
        this.payments = payments;
    }

    /** Trạng thái thanh toán của đơn (lệnh PENDING thì hỏi cổng qua bộ hãm). Không đụng {@code /orders/{id}} của ShopController. */
    @AuthenticatedOnly
    @GetMapping("/orders/{id}/payment")
    public ShopPaymentView myOrderPayment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ShopPaymentView.from(payments.myOrderPayment(RequestSupport.tenant(), RequestSupport.user(jwt), id));
    }

    @AuthenticatedOnly
    @GetMapping("/payment-options")
    public PaymentOptionsView paymentOptions() {
        return PaymentOptionsView.from(payments.paymentOptions(RequestSupport.tenant()));
    }
}
