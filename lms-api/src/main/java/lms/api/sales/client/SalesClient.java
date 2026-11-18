package lms.api.sales.client;

import java.util.List;
import java.util.UUID;
import lms.api.sales.dto.SalesDtos.*;

/**
 * Cổng sang /internal/sales/** của sales-service (cấu hình, kho, nhập kho, POS, đơn và thao tác đơn online, trả hàng,
 * hoàn tiền, nhật ký, phiếu in). Không có /shop/**: khách đi qua customer-api (ADR-0011), không qua lms-api.
 */
public interface SalesClient {

    SalesSettingsView getSettings();

    SalesSettingsView updateSettings(UpdateSalesSettingsRequest request);

    StockItemView registerStockItem(RegisterStockItemRequest request);

    SalesPage<StockItemView> searchStockItems(StockItemSearchQuery query);

    StockItemView getStockItem(UUID titleId);

    StockItemView updateStockItemPrice(UUID titleId, UpdateStockItemPriceRequest request);

    StockItemDetailsView getStockItemDetails(UUID titleId);

    StockItemDetailsView updateStockItemDetails(UUID titleId, UpdateStockItemDetailsRequest request);

    SalesPage<LedgerEntryView> stockLedger(UUID titleId, PageQuery query);

    StockItemView adjustStock(StockAdjustmentRequest request);

    StockItemView stocktake(StocktakeRequest request);

    SalesPage<SupplierView> searchSuppliers(SupplierSearchQuery query);

    SupplierView createSupplier(SupplierRequest request);

    SupplierView updateSupplier(UUID id, SupplierRequest request);

    GoodsReceiptView createGoodsReceipt(CreateGoodsReceiptRequest request);

    SalesPage<GoodsReceiptView> searchGoodsReceipts(GoodsReceiptSearchQuery query);

    GoodsReceiptView getGoodsReceipt(UUID id);

    GoodsReceiptView confirmGoodsReceipt(UUID id);

    GoodsReceiptView cancelGoodsReceipt(UUID id);

    SalesOrderView createPosOrder(PosOrderRequest request);

    SalesOrderView createPosOrderWithDiscount(PosOrderRequest request);

    SalesPage<SalesOrderView> searchOrders(SalesOrderSearchQuery query);

    SalesOrderView getOrder(UUID id);

    OrderContactView getOrderContact(UUID id);

    OrderNotesView getOrderNotes(UUID id);

    OrderNotesView updateOrderNotes(UUID id, UpdateOrderNotesRequest request);

    ReceiptView getReceipt(UUID id);

    // ---- Thao tác đơn online ----

    SalesOrderView confirmOrder(UUID id, ConfirmOrderRequest request);

    SalesOrderView applyOrderDiscount(UUID id, ApplyOrderDiscountRequest request);

    SalesOrderView markOrderPaid(UUID id, MarkOrderPaidRequest request);

    SalesOrderView markReadyForPickup(UUID id, OrderVersionRequest request);

    SalesOrderView markShipping(UUID id, OrderVersionRequest request);

    SalesOrderView completeOrder(UUID id, OrderVersionRequest request);

    SalesOrderView cancelOrder(UUID id, CancelOrderRequest request);

    // ---- Trả hàng, hoàn tiền, nhật ký ----

    ReturnView createReturn(CreateReturnRequest request);

    ReturnView createLateReturn(CreateLateReturnRequest request);

    ReturnView getReturn(UUID id);

    List<ReturnView> listReturnsByOrder(UUID orderId);

    SalesPage<RefundView> searchRefunds(RefundSearchQuery query);

    RefundView markRefunded(UUID id, MarkRefundedRequest request);

    SalesPage<AuditLogEntryView> searchAuditLog(AuditLogQuery query);

    // ---- Liên hệ của khách gửi cửa hàng (ADR-0015) ----

    SalesPage<ShopContactSummaryView> searchContactRequests(ShopContactSearchQuery query);

    ShopContactDetailView getContactRequest(UUID id);

    ShopContactContactView getContactRequestContact(UUID id);

    ShopContactDetailView updateContactRequestStatus(UUID id, UpdateShopContactStatusRequest request);

    ShopContactDetailView anonymizeContactRequest(UUID id);

    // ---- Quản trị banner trang chủ (ADR-0019 B5) ----

    List<BannerAdminView> listBanners();

    BannerAdminView getBanner(UUID id);

    BannerAdminView createBanner(SaveBannerRequest request);

    BannerAdminView updateBanner(UUID id, SaveBannerRequest request);

    void deleteBanner(UUID id);
}
