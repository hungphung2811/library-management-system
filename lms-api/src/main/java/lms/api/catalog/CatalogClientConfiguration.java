package lms.api.catalog;

import lms.api.catalog.client.CatalogClient;
import lms.api.catalog.client.http.HttpCatalogClient;
import lms.api.config.ServiceClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogClientConfiguration {

    @Bean
    CatalogClient catalogClient(ServiceClients clients) {
        return new HttpCatalogClient(clients.create(clients.urls().catalogUrl()));
    }
}
