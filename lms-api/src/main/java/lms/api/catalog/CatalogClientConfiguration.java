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

    @Bean
    lms.api.catalog.client.ReferenceCatalogClient referenceCatalogClient(ServiceClients clients) {
        return new lms.api.catalog.client.http.HttpReferenceCatalogClient(clients.create(clients.urls().catalogUrl()));
    }
}
