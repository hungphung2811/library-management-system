package lms.api.sales;

import lms.api.config.ServiceClients;
import lms.api.sales.client.SalesLogClient;
import lms.api.sales.client.http.HttpSalesLogClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client log vận hành (ADR-0029): cùng sales-service, đường dẫn /internal/sales/logs/**. */
@Configuration
public class SalesLogClientConfiguration {

    @Bean
    SalesLogClient salesLogClient(ServiceClients clients) {
        return new HttpSalesLogClient(clients.create(clients.urls().salesUrl()));
    }
}
