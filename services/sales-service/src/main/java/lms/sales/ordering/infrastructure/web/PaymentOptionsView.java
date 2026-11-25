package lms.sales.ordering.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lms.sales.ordering.application.ShopPaymentUseCases;

/**
 * Phương thức thanh toán website có thể dùng (ADR-0029 mục 3.1). {@code intentTtlMinutes} chỉ có với phương thức qua
 * cổng (SEPAY), các phương thức khác bỏ trường này. {@code deliveryFee} là phí giao hàng cố định của tenant (đồng).
 */
public record PaymentOptionsView(List<Method> methods, long deliveryFee) {

    public record Method(String method, List<String> fulfilments,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer intentTtlMinutes) {
    }

    static PaymentOptionsView from(ShopPaymentUseCases.PaymentOptions o) {
        return new PaymentOptionsView(o.methods().stream().map(m -> new Method(m.method().name(),
                m.fulfilments().stream().map(Enum::name).toList(), m.intentTtlMinutes())).toList(), o.deliveryFee());
    }
}
