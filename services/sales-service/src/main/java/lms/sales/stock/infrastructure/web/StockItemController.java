package lms.sales.stock.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.stock.application.GetStockItemUseCase;
import lms.sales.stock.application.GetStockLedgerUseCase;
import lms.sales.stock.application.ListStockItemsUseCase;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.sales.stock.application.StockItemDetailsUseCases;
import lms.sales.stock.application.StockItemView;
import lms.sales.stock.application.UpdateStockItemPriceUseCase;
import lms.sales.stock.domain.StockItemDetails;
import lms.sales.stock.domain.StockMovement;
import lms.security.permission.IntegrationScope;
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

    /**
     * Các trường sau lowStockThreshold đều tùy chọn (chi tiết quản lý). sku được chuẩn hóa (cắt khoảng trắng, chữ hoa)
     * và duy nhất theo tenant; costPrice là giá nhập (đồng).
     */
    public record RegisterRequest(@NotNull UUID titleId, @Min(0) Long sellingPrice, @Min(0) Integer lowStockThreshold,
                                  @Size(max = 40) String sku, @Size(max = 255) String supplier,
                                  @Min(0) Long costPrice, @Size(max = 64) String binLocation,
                                  @Min(0) Integer reorderQuantity, Instant lastRestockedAt,
                                  @Size(max = 500) String note) {
        StockItemDetails details() {
            return StockItemDetails.of(sku, supplier, costPrice, binLocation, reorderQuantity, lastRestockedAt, note);
        }

        @Override
        public String toString() {
            return "RegisterRequest[titleId=" + titleId + "]";
        }
    }

    /** Thay toàn bộ chi tiết; lastRestockedAt vắng thì giữ nguyên giá trị hiện có, các trường khác vắng thì xóa. */
    public record UpdateDetailsRequest(@Size(max = 40) String sku, @Size(max = 255) String supplier,
                                       @Min(0) Long costPrice, @Size(max = 64) String binLocation,
                                       @Min(0) Integer reorderQuantity, Instant lastRestockedAt,
                                       @Size(max = 500) String note) {
        StockItemDetails details() {
            return StockItemDetails.of(sku, supplier, costPrice, binLocation, reorderQuantity, lastRestockedAt, note);
        }

        @Override
        public String toString() {
            return "UpdateDetailsRequest[sku=" + sku + "]";
        }
    }

    /** Chi tiết đầy đủ gồm dữ liệu nội bộ (costPrice, note); khóa quyền riêng getDetails/updateDetails. */
    public record StockItemDetailsResponse(UUID titleId, String sku, String supplier, Long costPrice,
                                           String binLocation, Integer reorderQuantity, Instant lastRestockedAt,
                                           String note) {
        static StockItemDetailsResponse from(lms.sales.stock.domain.StockItem i) {
            StockItemDetails d = i.details();
            return new StockItemDetailsResponse(i.titleId(), d.sku(), d.supplier(),
                    d.costPrice() == null ? null : d.costPrice().amount(), d.binLocation(), d.reorderQuantity(),
                    d.lastRestockedAt(), d.note());
        }

        @Override
        public String toString() {
            return "StockItemDetailsResponse[titleId=" + titleId + "]";
        }
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
                                    Integer lowStockThreshold, String sku, String supplier, String binLocation,
                                    Integer reorderQuantity, Instant lastRestockedAt) {
        /** Không có costPrice và note (nội bộ): chỉ lấy qua {@link StockItemDetailsResponse}. */
        public static StockItemResponse from(StockItemView v) {
            var i = v.item();
            StockItemDetails d = i.details();
            return new StockItemResponse(i.titleId(), v.titleName(), (int) i.onHand(), (int) i.reserved(),
                    (int) i.available(), i.sellingPrice() == null ? null : i.sellingPrice().amount(),
                    v.effectivePrice() == null ? null : v.effectivePrice().amount(), v.lowStock(), i.active(),
                    i.lowStockThreshold(), d.sku(), d.supplier(), d.binLocation(), d.reorderQuantity(),
                    d.lastRestockedAt());
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
    private final StockItemDetailsUseCases details;

    public StockItemController(RegisterStockItemUseCase register, ListStockItemsUseCase list,
                               GetStockItemUseCase get, UpdateStockItemPriceUseCase updatePrice,
                               GetStockLedgerUseCase ledger, StockItemDetailsUseCases details) {
        this.register = register;
        this.list = list;
        this.get = get;
        this.updatePrice = updatePrice;
        this.ledger = ledger;
        this.details = details;
    }

    @PostMapping
    public ResponseEntity<StockItemResponse> register(@Valid @RequestBody RegisterRequest r,
                                                      @AuthenticationPrincipal Jwt jwt) {
        StockItemResponse created = StockItemResponse.from(register.execute(RequestSupport.tenant(),
                RequestSupport.user(jwt), r.titleId(), r.sellingPrice(), r.lowStockThreshold(), r.details()));
        return ResponseEntity.created(URI.create("/internal/sales/stock-items/" + created.titleId())).body(created);
    }

    @GetMapping
    @IntegrationScope("inventory:read")
    public PageResult<StockItemResponse> search(StockSearchQuery q) {
        return list.execute(RequestSupport.tenant(), Boolean.TRUE.equals(q.lowStockOnly()),
                RequestSupport.page(q.page()), RequestSupport.size(q.size())).map(StockItemResponse::from);
    }

    @GetMapping("/{titleId}")
    @IntegrationScope("inventory:read")
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

    @GetMapping("/{titleId}/details")
    public StockItemDetailsResponse getDetails(@PathVariable UUID titleId) {
        return StockItemDetailsResponse.from(details.get(RequestSupport.tenant(), titleId));
    }

    @PutMapping("/{titleId}/details")
    public StockItemDetailsResponse updateDetails(@PathVariable UUID titleId,
                                                  @Valid @RequestBody UpdateDetailsRequest r,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return StockItemDetailsResponse.from(details.update(RequestSupport.tenant(), RequestSupport.user(jwt), titleId,
                r.details()));
    }

    @GetMapping("/{titleId}/ledger")
    public PageResult<LedgerEntryResponse> ledger(@PathVariable UUID titleId, LedgerQuery q) {
        return ledger.execute(RequestSupport.tenant(), titleId, RequestSupport.page(q.page()),
                RequestSupport.size(q.size())).map(LedgerEntryResponse::from);
    }
}
