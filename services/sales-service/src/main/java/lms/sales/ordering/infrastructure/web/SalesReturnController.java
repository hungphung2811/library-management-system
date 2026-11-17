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
import lms.sales.ordering.application.CreateReturnCommand;
import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.application.ReturnQueryUseCases;
import lms.sales.ordering.application.ReturnResponse;
import lms.sales.ordering.domain.Disposition;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trả hàng. {@code create} (khóa SalesReturnController/create) áp cửa sổ N ngày: quá hạn thì 409
 * RETURN_WINDOW_EXPIRED. {@code createLate} (khóa riêng SalesReturnController/createLate, mặc định chỉ mẫu Quản lý cửa
 * hàng) bỏ kiểm N ngày, bắt buộc {@code lateReason} (400 LATE_REASON_REQUIRED) và ghi audit RETURN_LATE_APPROVED.
 * Hai endpoint thay vì một cờ vì phân quyền động chỉ kiểm theo endpoint (ADR-0003).
 */
@RestController
@RequestMapping("/internal/sales")
@RequiresModule(TenantModule.SALES)
public class SalesReturnController {

    public record ReturnLineRequest(@NotNull UUID orderLineId, @NotNull @Min(1) Integer quantity,
                                    @NotNull Disposition disposition) {
    }

    public record ReturnRequest(@NotNull UUID orderId, @NotEmpty @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> lines,
                                @NotBlank @Size(max = 500) String reason,
                                @NotBlank @Size(max = 100) String clientRequestId) {
    }

    public record LateReturnRequest(@NotNull UUID orderId,
                                    @NotEmpty @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> lines,
                                    @NotBlank @Size(max = 500) String reason,
                                    @Size(max = 500) String lateReason,
                                    @NotBlank @Size(max = 100) String clientRequestId) {
    }

    private final CreateReturnUseCase createReturn;
    private final ReturnQueryUseCases queries;

    public SalesReturnController(CreateReturnUseCase createReturn, ReturnQueryUseCases queries) {
        this.createReturn = createReturn;
        this.queries = queries;
    }

    @PostMapping("/returns")
    public ResponseEntity<ReturnResponse> create(@Valid @RequestBody ReturnRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return created(createReturn.create(command(request.orderId(), request.lines(), request.reason(), null,
                request.clientRequestId(), jwt)));
    }

    @PostMapping("/returns/late")
    public ResponseEntity<ReturnResponse> createLate(@Valid @RequestBody LateReturnRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return created(createReturn.createLate(command(request.orderId(), request.lines(), request.reason(),
                request.lateReason(), request.clientRequestId(), jwt)));
    }

    @GetMapping("/returns/{id}")
    public ReturnResponse get(@PathVariable UUID id) {
        return queries.get(RequestSupport.tenant(), id);
    }

    @GetMapping("/orders/{orderId}/returns")
    public List<ReturnResponse> listByOrder(@PathVariable UUID orderId) {
        return queries.listByOrder(RequestSupport.tenant(), orderId);
    }

    private static CreateReturnCommand command(UUID orderId, List<ReturnLineRequest> lines, String reason,
                                               String lateReason, String clientRequestId, Jwt jwt) {
        return new CreateReturnCommand(RequestSupport.tenant(), RequestSupport.user(jwt), orderId,
                lines.stream().map(l -> new CreateReturnCommand.Line(l.orderLineId(), l.quantity(),
                        l.disposition())).toList(), reason, lateReason,
                RequestSupport.clientRequestId(clientRequestId));
    }

    private static ResponseEntity<ReturnResponse> created(ReturnResponse r) {
        return ResponseEntity.created(URI.create("/internal/sales/returns/" + r.id())).body(r);
    }
}
