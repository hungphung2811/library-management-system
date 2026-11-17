package lms.sales.support;

import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.application.OrderWorkflowUseCases;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.application.ReturnQueryUseCases;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.shared.application.SalesAuditQueryUseCase;
import lms.sales.stock.application.StorefrontQueryUseCases;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.UpdateSalesSettingsUseCase;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.GetStockItemUseCase;
import lms.sales.stock.application.GetStockLedgerUseCase;
import lms.sales.stock.application.GoodsReceiptUseCases;
import lms.sales.stock.application.ListStockItemsUseCase;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.sales.stock.application.StocktakeUseCase;
import lms.sales.stock.application.SupplierUseCases;
import lms.sales.stock.application.UpdateStockItemPriceUseCase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Bean use case thật (bộ giả trong bộ nhớ của {@link Harness}) cho test WebMvc: controller chạy với nghiệp vụ thật,
 * chỉ tầng lưu trữ và catalog là giả. Harness dùng chung trong một context test nên mỗi test nên dùng đầu sách mới.
 */
@TestConfiguration
public class HarnessBeans {

    @Bean
    Harness harness() {
        return new Harness();
    }

    @Bean
    GetSalesSettingsUseCase getSalesSettingsUseCase(Harness h) {
        return h.getSettings;
    }

    @Bean
    UpdateSalesSettingsUseCase updateSalesSettingsUseCase(Harness h) {
        return h.updateSettings;
    }

    @Bean
    RegisterStockItemUseCase registerStockItemUseCase(Harness h) {
        return h.registerItem;
    }

    @Bean
    GetStockItemUseCase getStockItemUseCase(Harness h) {
        return h.getItem;
    }

    @Bean
    ListStockItemsUseCase listStockItemsUseCase(Harness h) {
        return new ListStockItemsUseCase(h.stockPort, h.catalog, h.getSettings);
    }

    @Bean
    GetStockLedgerUseCase getStockLedgerUseCase(Harness h) {
        return new GetStockLedgerUseCase(h.stockPort);
    }

    @Bean
    UpdateStockItemPriceUseCase updateStockItemPriceUseCase(Harness h) {
        return new UpdateStockItemPriceUseCase(h.stockPort, h.auditPort, h.clock);
    }

    @Bean
    AdjustStockUseCase adjustStockUseCase(Harness h) {
        return h.adjust;
    }

    @Bean
    StocktakeUseCase stocktakeUseCase(Harness h) {
        return h.stocktake;
    }

    @Bean
    SupplierUseCases supplierUseCases(Harness h) {
        return h.supplierUseCases;
    }

    @Bean
    GoodsReceiptUseCases goodsReceiptUseCases(Harness h) {
        return h.receiptUseCases;
    }

    @Bean
    PlacePosOrderUseCase placePosOrderUseCase(Harness h) {
        return h.placePos;
    }

    @Bean
    OrderQueryUseCases orderQueryUseCases(Harness h) {
        return h.orderQueries;
    }

    @Bean
    PlaceOnlineOrderUseCase placeOnlineOrderUseCase(Harness h) {
        return h.placeOnline;
    }

    @Bean
    OrderWorkflowUseCases orderWorkflowUseCases(Harness h) {
        return h.workflow;
    }

    @Bean
    ShopOrderUseCases shopOrderUseCases(Harness h) {
        return h.shopOrders;
    }

    @Bean
    CreateReturnUseCase createReturnUseCase(Harness h) {
        return h.createReturn;
    }

    @Bean
    ReturnQueryUseCases returnQueryUseCases(Harness h) {
        return h.returnQueries;
    }

    @Bean
    RefundUseCases refundUseCases(Harness h) {
        return h.refundUseCases;
    }

    @Bean
    StorefrontQueryUseCases storefrontQueryUseCases(Harness h) {
        return h.storefront;
    }

    @Bean
    SalesAuditQueryUseCase salesAuditQueryUseCase(Harness h) {
        return h.auditQueries;
    }
}
