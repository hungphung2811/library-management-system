package lms.sales.settings.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.settings.domain.OrderAbusePolicy;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chính sách chống lạm dụng đặt hàng online của tenant (hạn giữ hàng, trần đơn chờ, số lượng, tốc độ).
 * Khóa quyền: OrderAbusePolicyController/get, OrderAbusePolicyController/update. Không cache: use case đặt hàng đọc
 * trực tiếp và giá trị đổi phải có hiệu lực ngay.
 */
@RestController
@RequestMapping("/internal/sales/settings/order-abuse-policy")
@RequiresModule(TenantModule.SALES)
public class OrderAbusePolicyController {

    public record PolicyRequest(@NotNull Integer reservationMinutes, @NotNull Integer maxPendingOrders,
                                @NotNull Integer maxOrdersPerDay, @NotNull Integer maxQuantityPerLine,
                                @NotNull Integer maxTotalQuantity, @NotNull Integer ordersPerMinutePerUser,
                                @NotNull Integer ordersPerMinuteTenant, @NotNull Long version) {
    }

    public record PolicyResponse(int reservationMinutes, int maxPendingOrders, int maxOrdersPerDay,
                                 int maxQuantityPerLine, int maxTotalQuantity, int ordersPerMinutePerUser,
                                 int ordersPerMinuteTenant, long version, Instant updatedAt) {
        static PolicyResponse from(OrderAbusePolicy p) {
            var v = p.values();
            return new PolicyResponse(v.reservationMinutes(), v.maxPendingOrders(), v.maxOrdersPerDay(),
                    v.maxQuantityPerLine(), v.maxTotalQuantity(), v.ordersPerMinutePerUser(),
                    v.ordersPerMinuteTenant(), p.version(), p.updatedAt());
        }
    }

    private final OrderAbusePolicyUseCases useCases;

    public OrderAbusePolicyController(OrderAbusePolicyUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping
    public PolicyResponse get() {
        return PolicyResponse.from(useCases.get(RequestSupport.tenant()));
    }

    @PutMapping
    public PolicyResponse update(@Valid @RequestBody PolicyRequest r, @AuthenticationPrincipal Jwt jwt) {
        return PolicyResponse.from(useCases.update(RequestSupport.tenant(), RequestSupport.user(jwt),
                new OrderAbusePolicy.Values(r.reservationMinutes(), r.maxPendingOrders(), r.maxOrdersPerDay(),
                        r.maxQuantityPerLine(), r.maxTotalQuantity(), r.ordersPerMinutePerUser(),
                        r.ordersPerMinuteTenant()), r.version()));
    }
}
