package lms.sales.ordering.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import lms.sales.ordering.application.ShopPaymentUseCases;
import lms.sales.ordering.application.SyncGatewayPaymentUseCase;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.infrastructure.client.MomoPaymentGateway;
import lms.sales.ordering.infrastructure.client.SepayPaymentGateway;
import lms.sales.ordering.infrastructure.client.SimulatedPaymentGateway;
import lms.sales.ordering.infrastructure.client.ThrottledPaymentGateway;
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

    /** Hết hạn lệnh: hỏi cổng lần cuối trước khi đóng (ADR-0029 mục 2.3), best-effort. */
    @Bean
    ExpirePaymentIntentsUseCase expirePaymentIntentsUseCase(
            PaymentIntentRepository intents, PaymentIntentUseCases useCases, Clock clock,
            SyncGatewayPaymentUseCase gatewaySync,
            @Value("${app.payments.expire-batch-size:100}") int batchSize) {
        return new ExpirePaymentIntentsUseCase(intents, useCases, clock, batchSize, gatewaySync);
    }

    @Bean
    @ConditionalOnProperty(name = "app.payments.sandbox.enabled", havingValue = "true")
    PaymentGatewayPort simulatedPaymentGateway(
            @Value("${app.payments.sandbox.bank-bin:970422}") String bankBin,
            @Value("${app.payments.sandbox.account-no:0123456789}") String accountNo,
            @Value("${app.payments.sandbox.account-name:LMS DEMO}") String accountName) {
        return new SimulatedPaymentGateway(bankBin, accountNo, accountName);
    }

    /** Cổng MoMo sandbox (ví MoMo): chỉ có khi {@code app.payments.momo.enabled=true}; khóa lấy từ cấu hình/biến môi trường. */
    @Bean
    @ConditionalOnProperty(name = "app.payments.momo.enabled", havingValue = "true")
    PaymentGatewayPort momoPaymentGateway(
            ObjectMapper json,
            @Value("${app.payments.momo.endpoint:https://test-payment.momo.vn}") String endpoint,
            @Value("${app.payments.momo.partner-code}") String partnerCode,
            @Value("${app.payments.momo.access-key}") String accessKey,
            @Value("${app.payments.momo.secret-key}") String secretKey,
            @Value("${app.payments.momo.redirect-url:https://example.com/momo/return}") String redirectUrl,
            @Value("${app.payments.momo.ipn-url:https://example.com/momo/ipn}") String ipnUrl) {
        return new MomoPaymentGateway(json, endpoint, partnerCode, accessKey, secretKey, redirectUrl, ipnUrl);
    }

    /**
     * Cổng SePay Test mode (ADR-0028): chỉ có khi {@code app.payments.sepay.enabled=true}; token Test mode và tài khoản
     * lấy từ biến môi trường PAYMENTS_SEPAY_*, không có giá trị mặc định thật. Kết quả lấy bằng dò sổ giao dịch khi FE thăm dò.
     */
    @Bean
    @ConditionalOnProperty(name = "app.payments.sepay.enabled", havingValue = "true")
    PaymentGatewayPort sepayPaymentGateway(
            ObjectMapper json,
            @Value("${app.payments.sepay.endpoint:https://userapi-sandbox.sepay.vn}") String endpoint,
            @Value("${app.payments.sepay.token}") String token,
            @Value("${app.payments.sepay.bank-bin}") String bankBin,
            @Value("${app.payments.sepay.account-no}") String accountNo,
            @Value("${app.payments.sepay.account-name:LMS SEPAY TEST}") String accountName,
            @Value("${app.payments.sepay.bank-account-id:}") String bankAccountId,
            Clock clock,
            @Value("${app.payments.sepay.min-query-interval-ms:5000}") long minQueryIntervalMs,
            @Value("${app.payments.sepay.max-queries-per-second:2}") int maxQueriesPerSecond) {
        if (token.isBlank() || bankBin.isBlank() || accountNo.isBlank()) {
            throw new IllegalStateException("Bật app.payments.sepay thì phải đặt PAYMENTS_SEPAY_TOKEN, "
                    + "PAYMENTS_SEPAY_BANK_BIN và PAYMENTS_SEPAY_ACCOUNT_NO");
        }
        // Bọc bộ hãm (ADR-0029 mục 2.4): SePay giới hạn 3 yêu cầu/giây theo IP của cả máy chủ
        return new ThrottledPaymentGateway(
                new SepayPaymentGateway(json, endpoint, token, bankBin, accountNo, accountName, bankAccountId),
                clock, minQueryIntervalMs, maxQueriesPerSecond);
    }

    /**
     * Thanh toán trực tuyến của đơn online (ADR-0029): khách xem trạng thái/hủy lệnh, danh sách phương thức. SEPAY cho
     * website chỉ mở khi cờ {@code app.payments.online.sepay-enabled=true} VÀ cổng SePay có bean (mặc định tắt).
     */
    @Bean
    ShopPaymentUseCases shopPaymentUseCases(
            SalesOrderRepository orders, PaymentIntentRepository intents, SyncGatewayPaymentUseCase gatewaySync,
            PaymentIntentUseCases intentUseCases, GetSalesSettingsUseCase settings, Clock clock,
            ObjectProvider<PaymentGatewayPort> gateways,
            @Value("${app.payments.online.sepay-enabled:false}") boolean sepayOnlineFlag,
            @Value("${app.payments.online.intent-ttl-minutes:15}") int intentTtlMinutes) {
        boolean sepayOnline = sepayOnlineFlag && gateways.orderedStream()
                .anyMatch(g -> PaymentIntent.PROVIDER_SEPAY.equals(g.provider()));
        return new ShopPaymentUseCases(orders, intents, gatewaySync, intentUseCases, settings, clock, sepayOnline,
                intentTtlMinutes);
    }

    @Bean
    SyncGatewayPaymentUseCase syncGatewayPaymentUseCase(ObjectProvider<PaymentGatewayPort> gateways,
                                                        ProcessPaymentWebhookUseCase processor) {
        return new SyncGatewayPaymentUseCase(gateways.orderedStream().toList(), processor);
    }

    /** Luồng POS hai bước cho mọi phương thức (ADR-0021); cổng QR chỉ có khi sandbox bật, thiếu thì đơn QR bị 400. */
    @Bean
    CommitPosPendingOrderUseCase commitPosPendingOrderUseCase(
            SalesOrderRepository orders, PaymentIntentRepository intents, ObjectProvider<PaymentGatewayPort> gateways,
            StockService stockService, GetSalesSettingsUseCase settings, DocumentNumberPort numbers,
            SalesAuditPort audit, TenantDirectoryPort tenants, SalesReturnRepository returns, Clock clock,
            @Value("${app.payments.sandbox.intent-ttl-minutes:10}") long qrTtlMinutes,
            @Value("${app.payments.manual.intent-ttl-minutes:10}") long manualTtlMinutes) {
        return new CommitPosPendingOrderUseCase(orders, intents, gateways.orderedStream().toList(), stockService, settings,
                numbers, audit, tenants, returns, clock, Duration.ofMinutes(qrTtlMinutes),
                Duration.ofMinutes(manualTtlMinutes));
    }
}
