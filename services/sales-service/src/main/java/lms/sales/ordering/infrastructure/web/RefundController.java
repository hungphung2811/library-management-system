package lms.sales.ordering.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lms.sales.ordering.application.RefundResponse;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.shared.application.PageResult;
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

/** Hoàn tiền: danh sách (lọc status) và đánh dấu đã hoàn (phương thức gốc hoặc tiền mặt). */
@RestController
@RequestMapping("/internal/sales/refunds")
@RequiresModule(TenantModule.SALES)
public class RefundController {

    public record RefundSearchQuery(RefundStatus status, Integer page, Integer size) {
    }

    public record CompleteRefundRequest(@NotNull PaymentMethod method, @Size(max = 100) String reference,
                                        @Min(0) Long version) {
    }

    private final RefundUseCases refunds;

    public RefundController(RefundUseCases refunds) {
        this.refunds = refunds;
    }

    @GetMapping
    public PageResult<RefundResponse> search(RefundSearchQuery query) {
        return refunds.search(RequestSupport.tenant(), query.status(), RequestSupport.page(query.page()),
                RequestSupport.size(query.size()));
    }

    @PostMapping("/{id}/completion")
    public RefundResponse markRefunded(@PathVariable UUID id, @Valid @RequestBody CompleteRefundRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return refunds.markRefunded(RequestSupport.tenant(), RequestSupport.user(jwt), id, body.method(),
                body.reference(), body.version());
    }
}
