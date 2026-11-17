package lms.customer.catalog;

import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.client.http.HttpCatalogClient;
import lms.customer.config.ServiceClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogClientConfiguration {

    @Bean
    CatalogClient catalogClient(ServiceClients clients) {
        return new HttpCatalogClient(clients.create(ServiceClients.SALES, clients.urls().salesUrl()));
    }
}
