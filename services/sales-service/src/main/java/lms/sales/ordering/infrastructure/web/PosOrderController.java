package lms.sales.ordering.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.PosOrderResult;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bán tại quầy một bước. Hai endpoint thay vì một cờ vì phân quyền động chỉ kiểm theo endpoint (ADR-0003):
 * {@code create} (khóa PosOrderController/create) từ chối giảm giá bằng 400 DISCOUNT_NOT_ALLOWED;
 * {@code createWithDiscount} (khóa PosOrderController/createWithDiscount) cho phép giảm giá dòng và giảm giá đơn.
 */
@RestController
@RequestMapping("/internal/sales/pos-orders")
@RequiresModule(TenantModule.SALES)
public class PosOrderController {

    public record DiscountRequest(@NotNull Discount.Type type, @NotNull @Min(0) Long value) {
    }

    public record LineRequest(@NotNull UUID titleId, @NotNull @Min(1) Integer quantity,
                              @Valid DiscountRequest discount) {
    }

    public record WalkInRequest(@Size(max = 200) String name, @Size(max = 30) String phone) {
        @Override
        public String toString() { // dữ liệu cá nhân: không in
            return "WalkInRequest[***]";
        }
    }

    public record PosOrderRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull LineRequest> lines,
                                  @NotNull PaymentMethod paymentMethod, UUID customerUserId,
                                  @Valid WalkInRequest walkIn, @Valid DiscountRequest orderDiscount,
                                  @NotBlank @Size(max = 100) String clientRequestId,
                                  UUID exchangeOfReturnId, @Size(max = 500) String customerNote) {
        @Override
        public String toString() { // ghi chú của khách có thể chứa dữ liệu cá nhân: không in
            return "PosOrderRequest[lines=" + (lines == null ? 0 : lines.size()) + "]";
        }
    }

    private final PlacePosOrderUseCase placeOrder;

    public PosOrderController(PlacePosOrderUseCase placeOrder) {
        this.placeOrder = placeOrder;
    }

    @PostMapping
    public ResponseEntity<PosOrderView> create(@Valid @RequestBody PosOrderRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return created(placeOrder.createWithPayment(toCommand(request, jwt)));
    }

    @PostMapping("/discounted")
    public ResponseEntity<PosOrderView> createWithDiscount(@Valid @RequestBody PosOrderRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        return created(placeOrder.createWithDiscountAndPayment(toCommand(request, jwt)));
    }

    private static ResponseEntity<PosOrderView> created(PosOrderResult result) {
        return ResponseEntity.created(URI.create("/internal/sales/orders/" + result.order().id()))
                .body(PosOrderView.from(result));
    }

    private static PlacePosOrderCommand toCommand(PosOrderRequest r, Jwt jwt) {
        List<PlacePosOrderCommand.Line> lines = r.lines().stream()
                .map(l -> new PlacePosOrderCommand.Line(l.titleId(), l.quantity(), discount(l.discount())))
                .toList();
        return new PlacePosOrderCommand(RequestSupport.tenant(), RequestSupport.user(jwt), lines, r.paymentMethod(),
                r.customerUserId(), r.walkIn() == null ? null : r.walkIn().name(),
                r.walkIn() == null ? null : r.walkIn().phone(), discount(r.orderDiscount()),
                RequestSupport.clientRequestId(r.clientRequestId()), r.exchangeOfReturnId(), r.customerNote());
    }

    private static Discount discount(DiscountRequest d) {
        return d == null ? null : new Discount(d.type(), d.value());
    }
}
