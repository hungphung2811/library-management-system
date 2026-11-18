package lms.sales.ordering.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import lms.sales.ordering.application.AnonymizeWalkInBatchUseCase;
import lms.sales.ordering.application.AntiAbuseService;
import lms.sales.ordering.application.AnonymizeWalkInContactsUseCase;
import lms.sales.ordering.application.CommitOnlineOrderUseCase;
import lms.sales.ordering.application.CommitPosOrderUseCase;
import lms.sales.ordering.application.CommitPosPendingOrderUseCase;
import lms.sales.ordering.application.CommitReturnUseCase;
import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.application.CustomerOrderLockPort;
import lms.sales.ordering.application.ExpireOnlineReservationsUseCase;
import lms.sales.ordering.application.OnlineReservationExpiry;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.application.OrderWorkflowUseCases;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.RefundRepository;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.application.ReturnQueryUseCases;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.application.PaymentIntentRepository;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.ordering.application.ShopPaymentUseCases;
import lms.sales.ordering.application.WalkInAnonymizationPort;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shift.application.ShiftAttributionPort;
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
                                                      Clock clock, OrderAbusePolicyUseCases policies,
                                                      CustomerOrderLockPort customerLock,
                                                      AntiAbuseService antiAbuse, PaymentIntentRepository intents,
                                                      ObjectProvider<PaymentGatewayPort> gateways,
                                                      @Value("${app.payments.online.sepay-enabled:false}")
                                                      boolean sepayOnlineEnabled,
                                                      @Value("${app.payments.online.intent-ttl-minutes:15}")
                                                      long onlineIntentTtlMinutes) {
        // Thanh toán trực tuyến (ADR-0029): SEPAY chỉ đặt được khi cờ bật và cổng SePay có bean, thiếu thì 400 PAYMENT_NOT_ENABLED
        return new CommitOnlineOrderUseCase(orders, stockService, settings, numbers, audit, tenants, clock, policies,
                customerLock, antiAbuse, intents, gateways.orderedStream().toList(), sepayOnlineEnabled,
                Duration.ofMinutes(onlineIntentTtlMinutes));
    }

    /** Ngưỡng tỷ lệ hủy (%) của AntiAbuseService, cấu hình nền tảng (lms.sales.abuse.risk.*). */
    @Bean
    AntiAbuseService antiAbuseService(
            @Value("${lms.sales.abuse.risk.min-orders:5}") int minOrders,
            @Value("${lms.sales.abuse.risk.cancel-percent-medium:30}") int medium,
            @Value("${lms.sales.abuse.risk.cancel-percent-high:50}") int high,
            @Value("${lms.sales.abuse.risk.cancel-percent-critical:80}") int critical) {
        return new AntiAbuseService(new AntiAbuseService.Thresholds(minOrders, medium, high, critical));
    }

    @Bean
    PlaceOnlineOrderUseCase placeOnlineOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                                    CommitOnlineOrderUseCase commit,
                                                    OrderAbusePolicyUseCases policies) {
        return new PlaceOnlineOrderUseCase(orders, catalog, commit, policies);
    }

    @Bean
    OnlineReservationExpiry onlineReservationExpiry(SalesOrderRepository orders, OrderWorkflowUseCases workflow,
                                                    Clock clock) {
        return new OnlineReservationExpiry(orders, workflow, clock);
    }

    @Bean
    ExpireOnlineReservationsUseCase expireOnlineReservationsUseCase(
            SalesOrderRepository orders, OnlineReservationExpiry expiry, Clock clock,
            @Value("${lms.sales.abuse.reservation-expiry-batch-size:100}") int batchSize) {
        return new ExpireOnlineReservationsUseCase(orders, expiry, clock, batchSize);
    }

    @Bean
    OrderWorkflowUseCases orderWorkflowUseCases(SalesOrderRepository orders, RefundRepository refunds,
                                                StockService stockService, GetSalesSettingsUseCase settings,
                                                SalesAuditPort audit, Clock clock) {
        return new OrderWorkflowUseCases(orders, refunds, stockService, settings, audit, clock);
    }

    @Bean
    ShopOrderUseCases shopOrderUseCases(SalesOrderRepository orders, OrderWorkflowUseCases workflow,
                                        ShopPaymentUseCases payments) {
        return new ShopOrderUseCases(orders, workflow, payments);
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
                                  Clock clock, ShiftAttributionPort shifts) {
        return new RefundUseCases(refunds, orders, audit, clock).withShiftAttribution(shifts);
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
