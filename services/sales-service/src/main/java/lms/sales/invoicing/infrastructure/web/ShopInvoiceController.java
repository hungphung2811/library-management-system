package lms.sales.invoicing.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceRequestUseCases;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khách gửi yêu cầu xuất hóa đơn cho đơn ONLINE của chính mình (storefront, qua customer-api). Khách lấy từ token; đơn của
 * người khác, đơn POS và đơn không tồn tại đều 404 ORDER_NOT_FOUND cùng một thân (không lộ sự tồn tại). Phản hồi chỉ có
 * trạng thái yêu cầu (không lặp lại thông tin người mua). Khóa quyền: ShopInvoiceController/saveRequest (mẫu Khách hàng).
 */
@RestController
@RequestMapping("/internal/sales/shop/orders/{id}/invoice-request")
@RequiresModule(TenantModule.SALES)
public class ShopInvoiceController {

    public record ShopInvoiceRequest(@NotNull @Valid BuyerRequest buyer) {
        @Override
        public String toString() {
            return "ShopInvoiceRequest[***]";
        }
    }

    public record ShopInvoiceRequestView(UUID orderId, String status, long version) {
    }

    private final InvoiceRequestUseCases useCases;

    public ShopInvoiceController(InvoiceRequestUseCases useCases) {
        this.useCases = useCases;
    }

    @PutMapping
    public ShopInvoiceRequestView saveRequest(@PathVariable UUID id, @Valid @RequestBody ShopInvoiceRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        var saved = useCases.saveByCustomer(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                request.buyer().toInput());
        return new ShopInvoiceRequestView(saved.orderId(), saved.status(), saved.version());
    }
}
