package lms.api.sales;

import lms.api.config.ServiceClients;
import lms.api.sales.client.SalesShiftClient;
import lms.api.sales.client.http.HttpSalesShiftClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client ca thu ngân (ADR-0030 gói B): cùng sales-service, đường dẫn /internal/sales/shifts/**. */
@Configuration
public class SalesShiftClientConfiguration {

    @Bean
    SalesShiftClient salesShiftClient(ServiceClients clients) {
        return new HttpSalesShiftClient(clients.create(clients.urls().salesUrl()));
    }
}
