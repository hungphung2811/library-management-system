package lms.sales.stock.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.stock.application.GetStockItemUseCase;
import lms.sales.stock.application.GetStockLedgerUseCase;
import lms.sales.stock.application.ListStockItemsUseCase;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.sales.stock.application.StockItemView;
import lms.sales.stock.application.UpdateStockItemPriceUseCase;
import lms.sales.stock.domain.StockMovement;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.ResponseEntity;
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
 * Mặt hàng bán (đầu sách đã đưa vào bán), giá bán, sổ cái tồn kho. Khóa: register, search, get, updatePrice, ledger.
 */
@RestController
@RequestMapping("/internal/sales/stock-items")
@RequiresModule(TenantModule.SALES)
public class StockItemController {

    public record RegisterRequest(@NotNull UUID titleId, @Min(0) Long sellingPrice, @Min(0) Integer lowStockThreshold) {
    }

    /** sellingPrice null (gửi tường minh null) = quay về giá bìa của catalog. */
    public record UpdatePriceRequest(@Min(0) Long sellingPrice) {
    }

    /** keyword chưa được dùng (tên đầu sách nằm ở catalog); page bắt đầu từ 0. */
    public record StockSearchQuery(String keyword, Boolean lowStockOnly, Integer page, Integer size) {
    }

    public record LedgerQuery(Integer page, Integer size) {
    }

    public record StockItemResponse(UUID titleId, String titleName, int onHand, int reserved, int available,
                                    Long sellingPrice, Long effectivePrice, boolean lowStock, boolean active,
                                    Integer lowStockThreshold) {
        public static StockItemResponse from(StockItemView v) {
            var i = v.item();
            return new StockItemResponse(i.titleId(), v.titleName(), (int) i.onHand(), (int) i.reserved(),
                    (int) i.available(), i.sellingPrice() == null ? null : i.sellingPrice().amount(),
                    v.effectivePrice() == null ? null : v.effectivePrice().amount(), v.lowStock(), i.active(),
                    i.lowStockThreshold());
        }
    }

    public record LedgerEntryResponse(UUID id, UUID titleId, String type, int deltaOnHand, int deltaReserved,
                                      String refType, UUID refId, String reason, UUID actorId, Instant occurredAt) {
        static LedgerEntryResponse from(StockMovement m) {
            return new LedgerEntryResponse(m.id(), m.titleId(), m.type().name(), (int) m.deltaOnHand(),
                    (int) m.deltaReserved(), m.refType(), m.refId(), m.reason(), m.actorId(), m.occurredAt());
        }
    }

    private final RegisterStockItemUseCase register;
    private final ListStockItemsUseCase list;
    private final GetStockItemUseCase get;
    private final UpdateStockItemPriceUseCase updatePrice;
    private final GetStockLedgerUseCase ledger;

    public StockItemController(RegisterStockItemUseCase register, ListStockItemsUseCase list,
                               GetStockItemUseCase get, UpdateStockItemPriceUseCase updatePrice,
                               GetStockLedgerUseCase ledger) {
        this.register = register;
        this.list = list;
        this.get = get;
        this.updatePrice = updatePrice;
        this.ledger = ledger;
    }

    @PostMapping
    public ResponseEntity<StockItemResponse> register(@Valid @RequestBody RegisterRequest r,
                                                      @AuthenticationPrincipal Jwt jwt) {
        StockItemResponse created = StockItemResponse.from(register.execute(RequestSupport.tenant(),
                RequestSupport.user(jwt), r.titleId(), r.sellingPrice(), r.lowStockThreshold()));
        return ResponseEntity.created(URI.create("/internal/sales/stock-items/" + created.titleId())).body(created);
    }

    @GetMapping
    public PageResult<StockItemResponse> search(StockSearchQuery q) {
        return list.execute(RequestSupport.tenant(), Boolean.TRUE.equals(q.lowStockOnly()),
                RequestSupport.page(q.page()), RequestSupport.size(q.size())).map(StockItemResponse::from);
    }

    @GetMapping("/{titleId}")
    public StockItemResponse get(@PathVariable UUID titleId) {
        return StockItemResponse.from(get.execute(RequestSupport.tenant(), titleId));
    }

    @PutMapping("/{titleId}/price")
    public StockItemResponse updatePrice(@PathVariable UUID titleId, @Valid @RequestBody UpdatePriceRequest r,
                                         @AuthenticationPrincipal Jwt jwt) {
        UUID tenant = RequestSupport.tenant();
        updatePrice.execute(tenant, RequestSupport.user(jwt), titleId, r.sellingPrice());
        return StockItemResponse.from(get.execute(tenant, titleId));
    }

    @GetMapping("/{titleId}/ledger")
    public PageResult<LedgerEntryResponse> ledger(@PathVariable UUID titleId, LedgerQuery q) {
        return ledger.execute(RequestSupport.tenant(), titleId, RequestSupport.page(q.page()),
                RequestSupport.size(q.size())).map(LedgerEntryResponse::from);
    }
}
