package lms.sales.ordering.infrastructure.config;

import java.time.Clock;
import lms.sales.ordering.application.AnonymizeWalkInBatchUseCase;
import lms.sales.ordering.application.AnonymizeWalkInContactsUseCase;
import lms.sales.ordering.application.CommitOnlineOrderUseCase;
import lms.sales.ordering.application.CommitPosOrderUseCase;
import lms.sales.ordering.application.CommitPosPendingOrderUseCase;
import lms.sales.ordering.application.CommitReturnUseCase;
import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.application.OrderWorkflowUseCases;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.RefundRepository;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.application.ReturnQueryUseCases;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.ordering.application.WalkInAnonymizationPort;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StockService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root của context ordering. Thứ tự phụ thuộc: GetSalesSettingsUseCase, StockService (các context khác)
 * rồi CommitPosOrderUseCase (transactional) rồi PlacePosOrderUseCase (không transactional, tra catalog trước).
 */
@Configuration
public class OrderingBeanConfiguration {

    @Bean
    CommitPosOrderUseCase commitPosOrderUseCase(SalesOrderRepository orders, StockService stockService,
                                                GetSalesSettingsUseCase settings, DocumentNumberPort numbers,
                                                SalesAuditPort audit, Clock clock, TenantDirectoryPort tenants,
                                                SalesReturnRepository returns) {
        return new CommitPosOrderUseCase(orders, stockService, settings, numbers, audit, clock, tenants, returns);
    }

    @Bean
    PlacePosOrderUseCase placePosOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                              CommitPosOrderUseCase commit,
                                              ObjectProvider<CommitPosPendingOrderUseCase> qrCommit) {
        // Đơn QR (ADR-0020) chỉ có khi sandbox thanh toán bật; thiếu thì POS từ chối QR bằng 400
        return new PlacePosOrderUseCase(orders, catalog, commit, qrCommit.getIfAvailable());
    }

    @Bean
    OrderQueryUseCases orderQueryUseCases(SalesOrderRepository orders) {
        return new OrderQueryUseCases(orders);
    }

    @Bean
    CommitOnlineOrderUseCase commitOnlineOrderUseCase(SalesOrderRepository orders, StockService stockService,
                                                      GetSalesSettingsUseCase settings, DocumentNumberPort numbers,
                                                      SalesAuditPort audit, TenantDirectoryPort tenants,
                                                      Clock clock) {
        return new CommitOnlineOrderUseCase(orders, stockService, settings, numbers, audit, tenants, clock);
    }

    @Bean
    PlaceOnlineOrderUseCase placeOnlineOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                                    CommitOnlineOrderUseCase commit) {
        return new PlaceOnlineOrderUseCase(orders, catalog, commit);
    }

    @Bean
    OrderWorkflowUseCases orderWorkflowUseCases(SalesOrderRepository orders, RefundRepository refunds,
                                                StockService stockService, GetSalesSettingsUseCase settings,
                                                SalesAuditPort audit, Clock clock) {
        return new OrderWorkflowUseCases(orders, refunds, stockService, settings, audit, clock);
    }

    @Bean
    ShopOrderUseCases shopOrderUseCases(SalesOrderRepository orders, OrderWorkflowUseCases workflow) {
        return new ShopOrderUseCases(orders, workflow);
    }

    @Bean
    CommitReturnUseCase commitReturnUseCase(SalesOrderRepository orders, SalesReturnRepository returns,
                                            RefundRepository refunds, StockService stockService,
                                            GetSalesSettingsUseCase settings, DocumentNumberPort numbers,
                                            SalesAuditPort audit, Clock clock) {
        return new CommitReturnUseCase(orders, returns, refunds, stockService, settings, numbers, audit, clock);
    }

    @Bean
    CreateReturnUseCase createReturnUseCase(CommitReturnUseCase commit, SalesReturnRepository returns) {
        return new CreateReturnUseCase(commit, returns);
    }

    @Bean
    ReturnQueryUseCases returnQueryUseCases(SalesReturnRepository returns, RefundRepository refunds,
                                            SalesOrderRepository orders) {
        return new ReturnQueryUseCases(returns, refunds, orders);
    }

    @Bean
    RefundUseCases refundUseCases(RefundRepository refunds, SalesOrderRepository orders, SalesAuditPort audit,
                                  Clock clock) {
        return new RefundUseCases(refunds, orders, audit, clock);
    }

    @Bean
    AnonymizeWalkInBatchUseCase anonymizeWalkInBatchUseCase(WalkInAnonymizationPort port, SalesAuditPort audit) {
        return new AnonymizeWalkInBatchUseCase(port, audit);
    }

    @Bean
    AnonymizeWalkInContactsUseCase anonymizeWalkInContactsUseCase(
            GetSalesSettingsUseCase settings, AnonymizeWalkInBatchUseCase batch, Clock clock,
            @Value("${lms.sales.anonymize.batch-size:500}") int batchSize) {
        return new AnonymizeWalkInContactsUseCase(settings, batch, clock, batchSize);
    }
}
