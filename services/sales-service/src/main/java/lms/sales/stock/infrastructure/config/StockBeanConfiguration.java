package lms.sales.stock.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.GetStockItemUseCase;
import lms.sales.stock.application.GetStockLedgerUseCase;
import lms.sales.stock.application.GoodsReceiptCreator;
import lms.sales.stock.application.GoodsReceiptRepository;
import lms.sales.stock.application.GoodsReceiptUseCases;
import lms.sales.stock.application.ListStockItemsUseCase;
import lms.sales.stock.application.ReconcileStockUseCase;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.StockService;
import lms.sales.stock.application.StocktakeUseCase;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.application.SupplierUseCases;
import lms.sales.stock.application.UpdateStockItemPriceUseCase;
import lms.sales.stock.infrastructure.client.HttpCatalogLookupAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Composition root của context stock: use case là POJO, @Transactional nằm trong chính lớp use case. */
@Configuration
public class StockBeanConfiguration {

    @Bean
    CatalogLookupPort catalogLookupPort(@Value("${lms.sales.catalog.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new HttpCatalogLookupAdapter(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build());
    }

    @Bean
    StockService stockService(StockPort stock, Clock clock) {
        return new StockService(stock, clock);
    }

    @Bean
    RegisterStockItemUseCase registerStockItemUseCase(StockPort stock, CatalogLookupPort catalog,
                                                      GetSalesSettingsUseCase settings, SalesAuditPort audit,
                                                      Clock clock) {
        return new RegisterStockItemUseCase(stock, catalog, settings, audit, clock);
    }

    @Bean
    GetStockItemUseCase getStockItemUseCase(StockPort stock, CatalogLookupPort catalog,
                                            GetSalesSettingsUseCase settings) {
        return new GetStockItemUseCase(stock, catalog, settings);
    }

    @Bean
    ListStockItemsUseCase listStockItemsUseCase(StockPort stock, CatalogLookupPort catalog,
                                                GetSalesSettingsUseCase settings) {
        return new ListStockItemsUseCase(stock, catalog, settings);
    }

    @Bean
    GetStockLedgerUseCase getStockLedgerUseCase(StockPort stock) {
        return new GetStockLedgerUseCase(stock);
    }

    @Bean
    UpdateStockItemPriceUseCase updateStockItemPriceUseCase(StockPort stock, SalesAuditPort audit, Clock clock) {
        return new UpdateStockItemPriceUseCase(stock, audit, clock);
    }

    @Bean
    AdjustStockUseCase adjustStockUseCase(StockService stockService, SalesAuditPort audit, Clock clock) {
        return new AdjustStockUseCase(stockService, audit, clock);
    }

    @Bean
    StocktakeUseCase stocktakeUseCase(StockPort stock, StockService stockService, SalesAuditPort audit,
                                      Clock clock) {
        return new StocktakeUseCase(stock, stockService, audit, clock);
    }

    @Bean
    ReconcileStockUseCase reconcileStockUseCase(StockPort stock) {
        return new ReconcileStockUseCase(stock);
    }

    @Bean
    SupplierUseCases supplierUseCases(SupplierRepository suppliers, SalesAuditPort audit, Clock clock) {
        return new SupplierUseCases(suppliers, audit, clock);
    }

    @Bean
    GoodsReceiptCreator goodsReceiptCreator(GoodsReceiptRepository receipts, SupplierRepository suppliers,
                                            StockPort stock, DocumentNumberPort numbers, SalesAuditPort audit,
                                            Clock clock) {
        return new GoodsReceiptCreator(receipts, suppliers, stock, numbers, audit, clock);
    }

    @Bean
    GoodsReceiptUseCases goodsReceiptUseCases(GoodsReceiptCreator creator, GoodsReceiptRepository receipts,
                                              StockService stockService, SalesAuditPort audit, Clock clock) {
        return new GoodsReceiptUseCases(creator, receipts, stockService, audit, clock);
    }

    @Bean
    lms.sales.stock.application.StorefrontQueryUseCases storefrontQueryUseCases(StockPort stock,
                                                                              CatalogLookupPort catalog) {
        return new lms.sales.stock.application.StorefrontQueryUseCases(stock, catalog);
    }
}
