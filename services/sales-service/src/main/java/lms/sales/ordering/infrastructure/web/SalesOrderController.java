package lms.sales.ordering.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.application.OrderWorkflowUseCases;
import lms.sales.ordering.application.ReceiptResponse;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.IntegrationScope;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đơn (nhân viên). Đọc: search, get, getContact (liên hệ đầy đủ, khóa riêng), receipt; danh sách/chi tiết che một
 * phần liên hệ. Thao tác đơn online: confirm, applyDiscount (khóa riêng), markPaid, markReadyForPickup, markShipping,
 * complete, cancel. {@code version} (tùy chọn) là phiên bản đơn đã đọc: lệch thì 409 ORDER_STATE_CONFLICT.
 */
@RestController
@RequestMapping("/internal/sales/orders")
@RequiresModule(TenantModule.SALES)
public class SalesOrderController {

    /** from bao gồm, to loại trừ, theo thời điểm tạo đơn (ISO-8601); page bắt đầu từ 0. */
    public record OrderSearchQuery(OrderStatus status, PaymentStatus paymentStatus, OrderChannel channel,
                                   Instant from, Instant to, String keyword, Integer page, Integer size) {
    }

    public record ContactResponse(String name, String phone, String address, Instant piiAnonymizedAt) {
        @Override
        public String toString() {
            return "ContactResponse[***]";
        }
    }

    /** Thân tùy chọn của các thao tác chỉ cần phiên bản. */
    public record VersionRequest(@Min(0) Long version) {
    }

    public record ConfirmRequest(@Min(0) Long shippingFee, @Min(0) Long version) {
    }

    public record DiscountRequest(@NotNull Discount.Type type, @NotNull @Min(0) Long value) {
    }

    public record LineDiscountRequest(@NotNull @Min(1) Integer lineNo, @NotNull @Valid DiscountRequest discount) {
    }

    /** Mỗi lần gọi thay toàn bộ giảm giá; bỏ trống cả hai là gỡ giảm giá. */
    public record ApplyDiscountRequest(@Valid DiscountRequest orderDiscount,
                                       List<@Valid @NotNull LineDiscountRequest> lineDiscounts,
                                       @Min(0) Long version) {
    }

    public record PaymentRequest(@NotNull PaymentMethod method, @Size(max = 100) String reference,
                                 @Min(0) Long version) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason, @Min(0) Long version) {
    }

    /** Thay toàn bộ ghi chú nội bộ và ngày giao/nhận dự kiến (vắng/null là xóa). */
    public record NotesRequest(@Size(max = 500) String staffNote, LocalDate expectedDeliveryDate, @Min(0) Long version) {
        @Override
        public String toString() { // ghi chú nội bộ: không in
            return "NotesRequest[***]";
        }
    }

    /** Ghi chú đầy đủ gồm staffNote (nội bộ); khóa quyền riêng getNotes/updateNotes. */
    public record NotesResponse(UUID id, String customerNote, String staffNote, LocalDate expectedDeliveryDate,
                                long version) {
        static NotesResponse from(OrderWorkflowUseCases.OrderNotes n) {
            return new NotesResponse(n.orderId(), n.customerNote(), n.staffNote(), n.expectedDeliveryDate(),
                    n.version());
        }

        @Override
        public String toString() {
            return "NotesResponse[***]";
        }
    }

    private final OrderQueryUseCases queries;
    private final OrderWorkflowUseCases workflow;

    public SalesOrderController(OrderQueryUseCases queries, OrderWorkflowUseCases workflow) {
        this.queries = queries;
        this.workflow = workflow;
    }

    @GetMapping
    @IntegrationScope("orders:read")
    public PageResult<OrderView> search(OrderSearchQuery query) {
        return queries.search(RequestSupport.tenant(), new OrderQueryUseCases.SearchQuery(query.status(),
                        query.paymentStatus(), query.channel(), query.from(), query.to(), query.keyword(),
                        RequestSupport.page(query.page()), RequestSupport.size(query.size())))
                .map(OrderView::from);
    }

    @GetMapping("/{id}")
    @IntegrationScope("orders:read")
    public OrderView get(@PathVariable UUID id) {
        return OrderView.from(queries.get(RequestSupport.tenant(), id));
    }

    @GetMapping("/{id}/contact")
    public ContactResponse getContact(@PathVariable UUID id) {
        var c = queries.contact(RequestSupport.tenant(), id);
        return new ContactResponse(c.name(), c.phone(), c.address(), c.piiAnonymizedAt());
    }

    @GetMapping("/{id}/notes")
    public NotesResponse getNotes(@PathVariable UUID id) {
        return NotesResponse.from(workflow.notes(RequestSupport.tenant(), id));
    }

    @PutMapping("/{id}/notes")
    public NotesResponse updateNotes(@PathVariable UUID id, @RequestBody @Valid NotesRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return NotesResponse.from(workflow.updateNotes(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body.staffNote(), body.expectedDeliveryDate(), body.version()));
    }

    @GetMapping("/{id}/receipt")
    public ReceiptResponse receipt(@PathVariable UUID id) {
        return queries.receipt(RequestSupport.tenant(), id);
    }

    @PostMapping("/{id}/confirmation")
    public OrderView confirm(@PathVariable UUID id, @RequestBody(required = false) @Valid ConfirmRequest body,
                             @AuthenticationPrincipal Jwt jwt) {
        return OrderView.from(workflow.confirm(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body == null ? null : body.shippingFee(), body == null ? null : body.version()));
    }

    @PostMapping("/{id}/discount")
    public OrderView applyDiscount(@PathVariable UUID id, @RequestBody @Valid ApplyDiscountRequest body,
                                   @AuthenticationPrincipal Jwt jwt) {
        Map<Integer, Discount> lines = new HashMap<>();
        if (body.lineDiscounts() != null) {
            for (var l : body.lineDiscounts()) {
                if (lines.put(l.lineNo(), toDiscount(l.discount())) != null) {
                    throw lms.sales.shared.domain.SalesException.invalidRequest("Trùng dòng giảm giá");
                }
            }
        }
        return OrderView.from(workflow.applyDiscount(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                toDiscount(body.orderDiscount()), lines, body.version()));
    }

    @PostMapping("/{id}/payment")
    public OrderView markPaid(@PathVariable UUID id, @RequestBody @Valid PaymentRequest body,
                              @AuthenticationPrincipal Jwt jwt) {
        return OrderView.from(workflow.markPaid(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body.method(), body.reference(), body.version()));
    }

    @PostMapping("/{id}/ready-for-pickup")
    public OrderView markReadyForPickup(@PathVariable UUID id,
                                        @RequestBody(required = false) @Valid VersionRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return OrderView.from(workflow.markReadyForPickup(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body == null ? null : body.version()));
    }

    @PostMapping("/{id}/shipping")
    public OrderView markShipping(@PathVariable UUID id, @RequestBody(required = false) @Valid VersionRequest body,
                                  @AuthenticationPrincipal Jwt jwt) {
        return OrderView.from(workflow.markShipping(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body == null ? null : body.version()));
    }

    @PostMapping("/{id}/completion")
    public OrderView complete(@PathVariable UUID id, @RequestBody(required = false) @Valid VersionRequest body,
                              @AuthenticationPrincipal Jwt jwt) {
        return OrderView.from(workflow.complete(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body == null ? null : body.version()));
    }

    @PostMapping("/{id}/cancellation")
    public OrderView cancel(@PathVariable UUID id, @RequestBody @Valid CancelRequest body,
                            @AuthenticationPrincipal Jwt jwt) {
        return OrderView.from(workflow.cancel(RequestSupport.tenant(), RequestSupport.user(jwt), id, body.reason(),
                body.version()));
    }

    private static Discount toDiscount(DiscountRequest d) {
        return d == null ? null : new Discount(d.type(), d.value());
    }
}
