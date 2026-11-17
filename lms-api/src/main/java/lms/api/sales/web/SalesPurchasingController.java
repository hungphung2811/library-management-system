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

/** Nhà cung cấp và phiếu nhập kho. */
@RestController
@RequestMapping("/api/sales")
public class SalesPurchasingController {

    private final SalesClient sales;

    public SalesPurchasingController(SalesClient sales) {
        this.sales = sales;
    }

    @GetMapping("/suppliers")
    public SalesPage<SupplierView> searchSuppliers(SupplierSearchQuery query) {
        return sales.searchSuppliers(query);
    }

    @PostMapping("/suppliers")
    public ResponseEntity<SupplierView> createSupplier(@Valid @RequestBody SupplierRequest request) {
        SupplierView created = sales.createSupplier(request);
        return ResponseEntity.created(URI.create("/api/sales/suppliers/" + created.id())).body(created);
    }

    @PutMapping("/suppliers/{id}")
    public SupplierView updateSupplier(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return sales.updateSupplier(id, request);
    }

    @PostMapping("/goods-receipts")
    public ResponseEntity<GoodsReceiptView> createGoodsReceipt(@Valid @RequestBody CreateGoodsReceiptRequest request) {
        GoodsReceiptView created = sales.createGoodsReceipt(request);
        return ResponseEntity.created(URI.create("/api/sales/goods-receipts/" + created.id())).body(created);
    }

    @GetMapping("/goods-receipts")
    public SalesPage<GoodsReceiptView> searchGoodsReceipts(GoodsReceiptSearchQuery query) {
        return sales.searchGoodsReceipts(query);
    }

    @GetMapping("/goods-receipts/{id}")
    public GoodsReceiptView getGoodsReceipt(@PathVariable UUID id) {
        return sales.getGoodsReceipt(id);
    }

    @PostMapping("/goods-receipts/{id}/confirmation")
    public GoodsReceiptView confirmGoodsReceipt(@PathVariable UUID id) {
        return sales.confirmGoodsReceipt(id);
    }

    @PostMapping("/goods-receipts/{id}/cancellation")
    public GoodsReceiptView cancelGoodsReceipt(@PathVariable UUID id) {
        return sales.cancelGoodsReceipt(id);
    }
}
