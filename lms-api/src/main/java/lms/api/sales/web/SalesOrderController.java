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

/** Đơn POS (có/không giảm giá, có thể kèm exchangeOfReturnId), đọc và tìm đơn, liên hệ, phiếu in, thao tác đơn online. */
@RestController
@RequestMapping("/api/sales")
public class SalesOrderController {

    private final SalesClient sales;

    public SalesOrderController(SalesClient sales) {
        this.sales = sales;
    }

    /** Không giảm giá: nếu body có trường giảm giá thì sales-service trả 400 DISCOUNT_NOT_ALLOWED (BFF chuyển nguyên). */
    @PostMapping("/pos-orders")
    public ResponseEntity<SalesOrderView> createPosOrder(@Valid @RequestBody PosOrderRequest request) {
        SalesOrderView created = sales.createPosOrder(request);
        return ResponseEntity.created(URI.create("/api/sales/orders/" + created.id())).body(created);
    }

    @PostMapping("/pos-orders/discounted")
    public ResponseEntity<SalesOrderView> createPosOrderWithDiscount(@Valid @RequestBody PosOrderRequest request) {
        SalesOrderView created = sales.createPosOrderWithDiscount(request);
        return ResponseEntity.created(URI.create("/api/sales/orders/" + created.id())).body(created);
    }

    @GetMapping("/orders")
    public SalesPage<SalesOrderView> searchOrders(SalesOrderSearchQuery query) {
        return sales.searchOrders(query);
    }

    @GetMapping("/orders/{id}")
    public SalesOrderView getOrder(@PathVariable UUID id) {
        return sales.getOrder(id);
    }

    @GetMapping("/orders/{id}/contact")
    public OrderContactView getOrderContact(@PathVariable UUID id) {
        return sales.getOrderContact(id);
    }

    /** Ghi chú đầy đủ gồm staffNote (nội bộ): khóa quyền riêng, không có trong SalesOrderView. */
    @GetMapping("/orders/{id}/notes")
    public OrderNotesView getOrderNotes(@PathVariable UUID id) {
        return sales.getOrderNotes(id);
    }

    @PutMapping("/orders/{id}/notes")
    public OrderNotesView updateOrderNotes(@PathVariable UUID id, @Valid @RequestBody UpdateOrderNotesRequest request) {
        return sales.updateOrderNotes(id, request);
    }

    @GetMapping("/orders/{id}/receipt")
    public ReceiptView getReceipt(@PathVariable UUID id) {
        return sales.getReceipt(id);
    }

    // ---- Thao tác đơn online (sales-service kiểm quyền, trạng thái, phiên bản; lỗi 4xx chuyển nguyên) ----

    @PostMapping("/orders/{id}/confirmation")
    public SalesOrderView confirm(@PathVariable UUID id, @RequestBody(required = false) @Valid ConfirmOrderRequest body) {
        return sales.confirmOrder(id, body);
    }

    @PostMapping("/orders/{id}/discount")
    public SalesOrderView applyDiscount(@PathVariable UUID id, @Valid @RequestBody ApplyOrderDiscountRequest body) {
        return sales.applyOrderDiscount(id, body);
    }

    @PostMapping("/orders/{id}/payment")
    public SalesOrderView markPaid(@PathVariable UUID id, @Valid @RequestBody MarkOrderPaidRequest body) {
        return sales.markOrderPaid(id, body);
    }

    @PostMapping("/orders/{id}/ready-for-pickup")
    public SalesOrderView markReadyForPickup(@PathVariable UUID id,
                                             @RequestBody(required = false) @Valid OrderVersionRequest body) {
        return sales.markReadyForPickup(id, body);
    }

    @PostMapping("/orders/{id}/shipping")
    public SalesOrderView markShipping(@PathVariable UUID id,
                                       @RequestBody(required = false) @Valid OrderVersionRequest body) {
        return sales.markShipping(id, body);
    }

    @PostMapping("/orders/{id}/completion")
    public SalesOrderView complete(@PathVariable UUID id,
                                   @RequestBody(required = false) @Valid OrderVersionRequest body) {
        return sales.completeOrder(id, body);
    }

    @PostMapping("/orders/{id}/cancellation")
    public SalesOrderView cancel(@PathVariable UUID id, @Valid @RequestBody CancelOrderRequest body) {
        return sales.cancelOrder(id, body);
    }
}
