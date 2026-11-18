package lms.api.sales;

import lms.api.config.ServiceClients;
import lms.api.sales.client.SalesClient;
import lms.api.sales.client.SalesPaymentClient;
import lms.api.sales.client.http.HttpSalesClient;
import lms.api.sales.client.http.HttpSalesPaymentClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SalesClientConfiguration {

    @Bean
    SalesClient salesClient(ServiceClients clients) {
        return new HttpSalesClient(clients.create(clients.urls().salesUrl()));
    }

    /** Thanh toán QR của POS (ADR-0020): cùng sales-service, đường dẫn /internal/payments/**. */
    @Bean
    SalesPaymentClient salesPaymentClient(ServiceClients clients) {
        return new HttpSalesPaymentClient(clients.create(clients.urls().salesUrl()));
    }
}
