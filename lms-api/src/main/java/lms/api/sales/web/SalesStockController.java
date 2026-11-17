package lms.api.sales.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lms.api.sales.client.SalesClient;
import lms.api.sales.dto.SalesDtos.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cấu hình bán hàng, mặt hàng tồn và điều chỉnh/kiểm kho. sales-service kiểm quyền, module và tenant trên token được
 * chuyển tiếp; lms-api không kiểm.
 */
@RestController
@RequestMapping("/api/sales")
public class SalesStockController {

    private final SalesClient sales;

    public SalesStockController(SalesClient sales) {
        this.sales = sales;
    }

    @GetMapping("/settings")
    public SalesSettingsView getSettings() {
        return sales.getSettings();
    }

    @PutMapping("/settings")
    public SalesSettingsView updateSettings(@Valid @RequestBody UpdateSalesSettingsRequest request) {
        return sales.updateSettings(request);
    }

    @PostMapping("/stock-items")
    public ResponseEntity<StockItemView> registerStockItem(@Valid @RequestBody RegisterStockItemRequest request) {
        StockItemView created = sales.registerStockItem(request);
        return ResponseEntity.created(URI.create("/api/sales/stock-items/" + created.titleId())).body(created);
    }

    @GetMapping("/stock-items")
    public SalesPage<StockItemView> searchStockItems(StockItemSearchQuery query) {
        return sales.searchStockItems(query);
    }

    @GetMapping("/stock-items/{titleId}")
    public StockItemView getStockItem(@PathVariable UUID titleId) {
        return sales.getStockItem(titleId);
    }

    @PutMapping("/stock-items/{titleId}/price")
    public StockItemView updateStockItemPrice(@PathVariable UUID titleId,
                                              @Valid @RequestBody UpdateStockItemPriceRequest request) {
        return sales.updateStockItemPrice(titleId, request);
    }

    @GetMapping("/stock-items/{titleId}/ledger")
    public SalesPage<LedgerEntryView> stockLedger(@PathVariable UUID titleId, PageQuery query) {
        return sales.stockLedger(titleId, query);
    }

    @PostMapping("/stock-adjustments")
    public StockItemView adjustStock(@Valid @RequestBody StockAdjustmentRequest request) {
        return sales.adjustStock(request);
    }

    @PostMapping("/stocktakes")
    public StockItemView stocktake(@Valid @RequestBody StocktakeRequest request) {
        return sales.stocktake(request);
    }
}
