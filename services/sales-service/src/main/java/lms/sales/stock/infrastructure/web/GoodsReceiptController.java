package lms.sales.stock.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.stock.application.GoodsReceiptUseCases;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.ReceiptStatus;
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

/** Phiếu nhập kho. Khóa: create, search, get, confirm, cancel. */
@RestController
@RequestMapping("/internal/sales/goods-receipts")
@RequiresModule(TenantModule.SALES)
public class GoodsReceiptController {

    public record LineRequest(@NotNull UUID titleId, @NotNull @Min(1) Integer quantity,
                              @NotNull @Min(0) Long unitCost) {
    }

    public record CreateRequest(@NotNull UUID supplierId, @NotEmpty @Size(max = 200) List<@Valid @NotNull LineRequest> lines,
                                @NotBlank @Size(max = 100) String clientRequestId) {
    }

    /** status: DRAFT|CONFIRMED|CANCELLED; page bắt đầu từ 0. */
    public record ReceiptSearchQuery(ReceiptStatus status, UUID supplierId, Integer page, Integer size) {
    }

    public record LineResponse(UUID titleId, int quantity, long unitCost, long lineCost) {
    }

    public record ReceiptResponse(UUID id, String receiptNo, UUID supplierId, String status,
                                  List<LineResponse> lines, long totalCost, Instant createdAt, UUID createdBy,
                                  Instant confirmedAt, UUID confirmedBy, long version) {
        static ReceiptResponse from(GoodsReceipt r) {
            return new ReceiptResponse(r.id(), r.receiptNo(), r.supplierId(), r.status().name(),
                    r.lines().stream().map(l -> new LineResponse(l.titleId(), l.quantity(), l.unitCost().amount(),
                            l.unitCost().times(l.quantity()).amount())).toList(),
                    r.totalCost().amount(), r.createdAt(), r.createdBy(), r.confirmedAt(), r.confirmedBy(),
                    r.version());
        }
    }

    private final GoodsReceiptUseCases receipts;

    public GoodsReceiptController(GoodsReceiptUseCases receipts) {
        this.receipts = receipts;
    }

    @PostMapping
    public ResponseEntity<ReceiptResponse> create(@Valid @RequestBody CreateRequest r,
                                                  @AuthenticationPrincipal Jwt jwt) {
        List<GoodsReceiptUseCases.LineData> lines = r.lines().stream()
                .map(l -> new GoodsReceiptUseCases.LineData(l.titleId(), l.quantity(), l.unitCost())).toList();
        GoodsReceipt created = receipts.create(RequestSupport.tenant(), RequestSupport.user(jwt), r.supplierId(),
                lines, RequestSupport.clientRequestId(r.clientRequestId()));
        return ResponseEntity.created(URI.create("/internal/sales/goods-receipts/" + created.id()))
                .body(ReceiptResponse.from(created));
    }

    @GetMapping
    public PageResult<ReceiptResponse> search(ReceiptSearchQuery q) {
        return receipts.search(RequestSupport.tenant(), q.status(), q.supplierId(), RequestSupport.page(q.page()),
                RequestSupport.size(q.size())).map(ReceiptResponse::from);
    }

    @GetMapping("/{id}")
    public ReceiptResponse get(@PathVariable UUID id) {
        return ReceiptResponse.from(receipts.get(RequestSupport.tenant(), id));
    }

    @PostMapping("/{id}/confirmation")
    public ReceiptResponse confirm(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ReceiptResponse.from(receipts.confirm(RequestSupport.tenant(), RequestSupport.user(jwt), id));
    }

    @PostMapping("/{id}/cancellation")
    public ReceiptResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ReceiptResponse.from(receipts.cancel(RequestSupport.tenant(), RequestSupport.user(jwt), id));
    }
}
