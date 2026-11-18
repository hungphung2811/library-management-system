package lms.sales.invoicing.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceRequestUseCases;
import lms.sales.invoicing.application.InvoiceRequestView;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yêu cầu xuất hóa đơn của một đơn do nhân viên nhập (POS: thông tin người mua tại quầy; đơn online: nhập thay khách).
 * Khóa quyền: InvoiceRequestController/get, InvoiceRequestController/save.
 */
@RestController
@RequestMapping("/internal/sales/orders/{orderId}/invoice-request")
@RequiresModule(TenantModule.SALES)
public class InvoiceRequestController {

    /** Thay toàn bộ thông tin người mua (cần tên cá nhân hoặc tên đơn vị). */
    public record SaveInvoiceRequest(@NotNull @Valid BuyerRequest buyer) {
        @Override
        public String toString() {
            return "SaveInvoiceRequest[***]";
        }
    }

    private final InvoiceRequestUseCases useCases;

    public InvoiceRequestController(InvoiceRequestUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping
    public InvoiceRequestView get(@PathVariable UUID orderId) {
        return useCases.get(RequestSupport.tenant(), orderId);
    }

    @PutMapping
    public InvoiceRequestView save(@PathVariable UUID orderId, @Valid @RequestBody SaveInvoiceRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return useCases.saveByStaff(RequestSupport.tenant(), RequestSupport.user(jwt), orderId,
                request.buyer().toInput());
    }
}
