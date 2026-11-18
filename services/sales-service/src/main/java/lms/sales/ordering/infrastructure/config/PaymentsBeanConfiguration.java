package lms.sales.ordering.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import lms.sales.ordering.application.CommitPosPendingOrderUseCase;
import lms.sales.ordering.application.ExpirePaymentIntentsUseCase;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.application.PaymentIntentRepository;
import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.infrastructure.client.SimulatedPaymentGateway;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.stock.application.StockService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root của thanh toán POS hai bước (ADR-0020, ADR-0021). Luồng tạo đơn và xác nhận/hủy/hết hạn luôn có bean;
 * riêng cổng QR giả lập chỉ có khi {@code app.payments.sandbox.enabled=true} (mặc định tắt; dev bật, prod tắt), thiếu thì
 * POS từ chối paymentMethod QR bằng 400 PAYMENT_NOT_ENABLED. Thời hạn lệnh thu tay: {@code app.payments.manual.intent-ttl-minutes}.
 */
@Configuration
public class PaymentsBeanConfiguration {

    @Bean
    PaymentIntentUseCases paymentIntentUseCases(PaymentIntentRepository intents, SalesOrderRepository orders,
                                                StockService stockService, SalesAuditPort audit, Clock clock) {
        return new PaymentIntentUseCases(intents, orders, stockService, audit, clock);
    }

    @Bean
    ProcessPaymentWebhookUseCase processPaymentWebhookUseCase(PaymentIntentRepository intents,
                                                              SalesOrderRepository orders, StockService stockService,
                                                              SalesAuditPort audit, Clock clock) {
        return new ProcessPaymentWebhookUseCase(intents, orders, stockService, audit, clock);
    }

    @Bean
    ExpirePaymentIntentsUseCase expirePaymentIntentsUseCase(
            PaymentIntentRepository intents, PaymentIntentUseCases useCases, Clock clock,
            @Value("${app.payments.expire-batch-size:100}") int batchSize) {
        return new ExpirePaymentIntentsUseCase(intents, useCases, clock, batchSize);
    }

    @Bean
    @ConditionalOnProperty(name = "app.payments.sandbox.enabled", havingValue = "true")
    PaymentGatewayPort simulatedPaymentGateway(
            @Value("${app.payments.sandbox.bank-bin:970422}") String bankBin,
            @Value("${app.payments.sandbox.account-no:0123456789}") String accountNo,
            @Value("${app.payments.sandbox.account-name:LMS DEMO}") String accountName) {
        return new SimulatedPaymentGateway(bankBin, accountNo, accountName);
    }

    /** Luồng POS hai bước cho mọi phương thức (ADR-0021); cổng QR chỉ có khi sandbox bật, thiếu thì đơn QR bị 400. */
    @Bean
    CommitPosPendingOrderUseCase commitPosPendingOrderUseCase(
            SalesOrderRepository orders, PaymentIntentRepository intents, ObjectProvider<PaymentGatewayPort> gateway,
            StockService stockService, GetSalesSettingsUseCase settings, DocumentNumberPort numbers,
            SalesAuditPort audit, TenantDirectoryPort tenants, SalesReturnRepository returns, Clock clock,
            @Value("${app.payments.sandbox.intent-ttl-minutes:10}") long qrTtlMinutes,
            @Value("${app.payments.manual.intent-ttl-minutes:10}") long manualTtlMinutes) {
        return new CommitPosPendingOrderUseCase(orders, intents, gateway.getIfAvailable(), stockService, settings,
                numbers, audit, tenants, returns, clock, Duration.ofMinutes(qrTtlMinutes),
                Duration.ofMinutes(manualTtlMinutes));
    }
}
