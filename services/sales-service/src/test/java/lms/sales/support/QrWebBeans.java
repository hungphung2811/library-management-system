package lms.sales.support;

import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.PaymentTenantResolverPort;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Bean cho test WebMvc thanh toán QR: use case thật trên bộ giả của {@link Harness}/{@link QrFixture}. Không dùng
 * chung với {@link HarnessBeans} (cả hai đều định nghĩa Harness và PlacePosOrderUseCase).
 */
@TestConfiguration
public class QrWebBeans {

    @Bean
    Harness harness() {
        return new Harness();
    }

    @Bean
    QrFixture qrFixture(Harness h) {
        return new QrFixture(h);
    }

    @Bean
    PlacePosOrderUseCase placePosOrderUseCase(QrFixture fx) {
        return fx.placePos;
    }

    @Bean
    PaymentIntentUseCases paymentIntentUseCases(QrFixture fx) {
        return fx.intentUseCases;
    }

    @Bean
    ProcessPaymentWebhookUseCase processPaymentWebhookUseCase(QrFixture fx) {
        return fx.webhook;
    }

    @Bean
    lms.sales.ordering.application.SyncGatewayPaymentUseCase syncGatewayPaymentUseCase(QrFixture fx) {
        return fx.gatewaySync;
    }

    @Bean
    PaymentTenantResolverPort paymentTenantResolverPort(QrFixture fx) {
        return fx.resolver;
    }
}
