package lms.api.sales.client.http;

import java.util.List;
import java.util.UUID;
import lms.api.sales.client.SalesClient;
import lms.api.sales.dto.SalesDtos.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Gọi /internal/sales/** của sales-service. Token và correlation id do RestClient chuyển tiếp. */
public class HttpSalesClient implements SalesClient {

    private static final String BASE = "/internal/sales";

    private static final ParameterizedTypeReference<SalesPage<StockItemView>> STOCK_PAGE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<SalesPage<LedgerEntryView>> LEDGER_PAGE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<SalesPage<SupplierView>> SUPPLIER_PAGE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<SalesPage<GoodsReceiptView>> RECEIPT_PAGE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<SalesPage<SalesOrderView>> ORDER_PAGE = new ParameterizedTypeReference<>() { };

    private static final ParameterizedTypeReference<List<ReturnView>> RETURN_LIST = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<SalesPage<RefundView>> REFUND_PAGE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<SalesPage<AuditLogEntryView>> AUDIT_PAGE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<SalesPage<ShopContactSummaryView>> CONTACT_PAGE = new ParameterizedTypeReference<>() { };

    private static final ParameterizedTypeReference<List<BannerAdminView>> BANNER_LIST = new ParameterizedTypeReference<>() { };

    private final RestClient client;

    public HttpSalesClient(RestClient client) {
        this.client = client;
    }

    private static void param(UriBuilder b, String name, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        b.queryParam(name, value);
    }

    private static <T> SalesPage<T> orEmpty(SalesPage<T> page) {
        return page == null ? new SalesPage<>(List.of(), 0, 0, 0) : page;
    }

    @Override
    public SalesSettingsView getSettings() {
        return client.get().uri(BASE + "/settings").retrieve().body(SalesSettingsView.class);
    }

    @Override
    public SalesSettingsView updateSettings(UpdateSalesSettingsRequest request) {
        return client.put().uri(BASE + "/settings").body(request).retrieve().body(SalesSettingsView.class);
    }

    @Override
    public OrderAbusePolicyView getOrderAbusePolicy() {
        return client.get().uri(BASE + "/settings/order-abuse-policy").retrieve().body(OrderAbusePolicyView.class);
    }

    @Override
    public OrderAbusePolicyView updateOrderAbusePolicy(UpdateOrderAbusePolicyRequest request) {
        return client.put().uri(BASE + "/settings/order-abuse-policy").body(request).retrieve()
                .body(OrderAbusePolicyView.class);
    }

    @Override
    public StockItemView registerStockItem(RegisterStockItemRequest request) {
        return client.post().uri(BASE + "/stock-items").body(request).retrieve().body(StockItemView.class);
    }

    @Override
    public SalesPage<StockItemView> searchStockItems(StockItemSearchQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/stock-items");
            param(b, "keyword", q.keyword());
            param(b, "lowStockOnly", q.lowStockOnly());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(STOCK_PAGE));
    }

    @Override
    public StockItemView getStockItem(UUID titleId) {
        return client.get().uri(BASE + "/stock-items/{titleId}", titleId).retrieve().body(StockItemView.class);
    }

    @Override
    public StockItemView updateStockItemPrice(UUID titleId, UpdateStockItemPriceRequest request) {
        return client.put().uri(BASE + "/stock-items/{titleId}/price", titleId).body(request).retrieve()
                .body(StockItemView.class);
    }

    @Override
    public StockItemDetailsView getStockItemDetails(UUID titleId) {
        return client.get().uri(BASE + "/stock-items/{titleId}/details", titleId).retrieve()
                .body(StockItemDetailsView.class);
    }

    @Override
    public StockItemDetailsView updateStockItemDetails(UUID titleId, UpdateStockItemDetailsRequest request) {
        return client.put().uri(BASE + "/stock-items/{titleId}/details", titleId).body(request).retrieve()
                .body(StockItemDetailsView.class);
    }

    @Override
    public SalesPage<LedgerEntryView> stockLedger(UUID titleId, PageQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/stock-items/{titleId}/ledger");
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build(titleId);
        }).retrieve().body(LEDGER_PAGE));
    }

    @Override
    public StockItemView adjustStock(StockAdjustmentRequest request) {
        return client.post().uri(BASE + "/stock-adjustments").body(request).retrieve().body(StockItemView.class);
    }

    @Override
    public StockItemView stocktake(StocktakeRequest request) {
        return client.post().uri(BASE + "/stocktakes").body(request).retrieve().body(StockItemView.class);
    }

    @Override
    public SalesPage<SupplierView> searchSuppliers(SupplierSearchQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/suppliers");
            param(b, "keyword", q.keyword());
            param(b, "activeOnly", q.activeOnly());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(SUPPLIER_PAGE));
    }

    @Override
    public SupplierView createSupplier(SupplierRequest request) {
        return client.post().uri(BASE + "/suppliers").body(request).retrieve().body(SupplierView.class);
    }

    @Override
    public SupplierView updateSupplier(UUID id, SupplierRequest request) {
        return client.put().uri(BASE + "/suppliers/{id}", id).body(request).retrieve().body(SupplierView.class);
    }

    @Override
    public GoodsReceiptView createGoodsReceipt(CreateGoodsReceiptRequest request) {
        return client.post().uri(BASE + "/goods-receipts").body(request).retrieve().body(GoodsReceiptView.class);
    }

    @Override
    public SalesPage<GoodsReceiptView> searchGoodsReceipts(GoodsReceiptSearchQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/goods-receipts");
            param(b, "status", q.status());
            param(b, "supplierId", q.supplierId());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(RECEIPT_PAGE));
    }

    @Override
    public GoodsReceiptView getGoodsReceipt(UUID id) {
        return client.get().uri(BASE + "/goods-receipts/{id}", id).retrieve().body(GoodsReceiptView.class);
    }

    @Override
    public GoodsReceiptView confirmGoodsReceipt(UUID id) {
        return client.post().uri(BASE + "/goods-receipts/{id}/confirmation", id).retrieve().body(GoodsReceiptView.class);
    }

    @Override
    public GoodsReceiptView cancelGoodsReceipt(UUID id) {
        return client.post().uri(BASE + "/goods-receipts/{id}/cancellation", id).retrieve().body(GoodsReceiptView.class);
    }

    @Override
    public SalesOrderView createPosOrder(PosOrderRequest request) {
        return client.post().uri(BASE + "/pos-orders").body(request).retrieve().body(SalesOrderView.class);
    }

    @Override
    public SalesOrderView createPosOrderWithDiscount(PosOrderRequest request) {
        return client.post().uri(BASE + "/pos-orders/discounted").body(request).retrieve().body(SalesOrderView.class);
    }

    @Override
    public SalesPage<SalesOrderView> searchOrders(SalesOrderSearchQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/orders");
            param(b, "status", q.status());
            param(b, "paymentStatus", q.paymentStatus());
            param(b, "channel", q.channel());
            param(b, "from", q.from());
            param(b, "to", q.to());
            param(b, "keyword", q.keyword());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(ORDER_PAGE));
    }

    @Override
    public SalesOrderView getOrder(UUID id) {
        return client.get().uri(BASE + "/orders/{id}", id).retrieve().body(SalesOrderView.class);
    }

    @Override
    public OrderContactView getOrderContact(UUID id) {
        return client.get().uri(BASE + "/orders/{id}/contact", id).retrieve().body(OrderContactView.class);
    }

    @Override
    public OrderNotesView getOrderNotes(UUID id) {
        return client.get().uri(BASE + "/orders/{id}/notes", id).retrieve().body(OrderNotesView.class);
    }

    @Override
    public OrderNotesView updateOrderNotes(UUID id, UpdateOrderNotesRequest request) {
        return client.put().uri(BASE + "/orders/{id}/notes", id).body(request).retrieve().body(OrderNotesView.class);
    }

    @Override
    public ReceiptView getReceipt(UUID id) {
        return client.get().uri(BASE + "/orders/{id}/receipt", id).retrieve().body(ReceiptView.class);
    }

    private SalesOrderView orderAction(UUID id, String action, Object body) {
        var spec = client.post().uri(BASE + "/orders/{id}/" + action, id);
        return (body == null ? spec : spec.body(body)).retrieve().body(SalesOrderView.class);
    }

    @Override
    public SalesOrderView confirmOrder(UUID id, ConfirmOrderRequest request) {
        return orderAction(id, "confirmation", request);
    }

    @Override
    public SalesOrderView applyOrderDiscount(UUID id, ApplyOrderDiscountRequest request) {
        return orderAction(id, "discount", request);
    }

    @Override
    public SalesOrderView markOrderPaid(UUID id, MarkOrderPaidRequest request) {
        return orderAction(id, "payment", request);
    }

    @Override
    public SalesOrderView markReadyForPickup(UUID id, OrderVersionRequest request) {
        return orderAction(id, "ready-for-pickup", request);
    }

    @Override
    public SalesOrderView markShipping(UUID id, OrderVersionRequest request) {
        return orderAction(id, "shipping", request);
    }

    @Override
    public SalesOrderView completeOrder(UUID id, OrderVersionRequest request) {
        return orderAction(id, "completion", request);
    }

    @Override
    public SalesOrderView cancelOrder(UUID id, CancelOrderRequest request) {
        return orderAction(id, "cancellation", request);
    }

    @Override
    public ReturnView createReturn(CreateReturnRequest request) {
        return client.post().uri(BASE + "/returns").body(request).retrieve().body(ReturnView.class);
    }

    @Override
    public ReturnView createLateReturn(CreateLateReturnRequest request) {
        return client.post().uri(BASE + "/returns/late").body(request).retrieve().body(ReturnView.class);
    }

    @Override
    public ReturnView getReturn(UUID id) {
        return client.get().uri(BASE + "/returns/{id}", id).retrieve().body(ReturnView.class);
    }

    @Override
    public List<ReturnView> listReturnsByOrder(UUID orderId) {
        List<ReturnView> r = client.get().uri(BASE + "/orders/{id}/returns", orderId).retrieve().body(RETURN_LIST);
        return r == null ? List.of() : r;
    }

    @Override
    public SalesPage<RefundView> searchRefunds(RefundSearchQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/refunds");
            param(b, "status", q.status());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(REFUND_PAGE));
    }

    @Override
    public RefundView markRefunded(UUID id, MarkRefundedRequest request) {
        return client.post().uri(BASE + "/refunds/{id}/completion", id).body(request).retrieve().body(RefundView.class);
    }

    @Override
    public SalesPage<AuditLogEntryView> searchAuditLog(AuditLogQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/audit-log");
            param(b, "action", q.action());
            param(b, "aggregateType", q.aggregateType());
            param(b, "aggregateId", q.aggregateId());
            param(b, "from", q.from());
            param(b, "to", q.to());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(AUDIT_PAGE));
    }

    @Override
    public SalesPage<ShopContactSummaryView> searchContactRequests(ShopContactSearchQuery q) {
        return orEmpty(client.get().uri(b -> {
            b.path(BASE + "/contact-requests");
            param(b, "status", q.status());
            param(b, "topic", q.topic());
            param(b, "from", q.from());
            param(b, "to", q.to());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(CONTACT_PAGE));
    }

    @Override
    public ShopContactDetailView getContactRequest(UUID id) {
        return client.get().uri(BASE + "/contact-requests/{id}", id).retrieve().body(ShopContactDetailView.class);
    }

    @Override
    public ShopContactContactView getContactRequestContact(UUID id) {
        return client.get().uri(BASE + "/contact-requests/{id}/contact", id).retrieve()
                .body(ShopContactContactView.class);
    }

    @Override
    public ShopContactDetailView updateContactRequestStatus(UUID id, UpdateShopContactStatusRequest request) {
        return client.put().uri(BASE + "/contact-requests/{id}/status", id).body(request).retrieve()
                .body(ShopContactDetailView.class);
    }

    @Override
    public ShopContactDetailView anonymizeContactRequest(UUID id) {
        return client.post().uri(BASE + "/contact-requests/{id}/anonymization", id).retrieve()
                .body(ShopContactDetailView.class);
    }

    @Override
    public List<BannerAdminView> listBanners() {
        List<BannerAdminView> r = client.get().uri(BASE + "/banners").retrieve().body(BANNER_LIST);
        return r == null ? List.of() : r;
    }

    @Override
    public BannerAdminView getBanner(UUID id) {
        return client.get().uri(BASE + "/banners/{id}", id).retrieve().body(BannerAdminView.class);
    }

    @Override
    public BannerAdminView createBanner(SaveBannerRequest request) {
        return client.post().uri(BASE + "/banners").body(request).retrieve().body(BannerAdminView.class);
    }

    @Override
    public BannerAdminView updateBanner(UUID id, SaveBannerRequest request) {
        return client.put().uri(BASE + "/banners/{id}", id).body(request).retrieve().body(BannerAdminView.class);
    }

    @Override
    public void deleteBanner(UUID id) {
        client.delete().uri(BASE + "/banners/{id}", id).retrieve().toBodilessEntity();
    }
}
