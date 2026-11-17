package lms.api.sales;

import lms.api.config.ServiceClients;
import lms.api.sales.client.SalesClient;
import lms.api.sales.client.http.HttpSalesClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SalesClientConfiguration {

    @Bean
    SalesClient salesClient(ServiceClients clients) {
        return new HttpSalesClient(clients.create(clients.urls().salesUrl()));
    }
}
