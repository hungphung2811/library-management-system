package lms.integration.catalog;

import lms.integration.catalog.client.CatalogClient;
import lms.integration.catalog.client.http.HttpCatalogClient;
import lms.integration.config.DownstreamRestClients;
import lms.integration.config.ServicesProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Nối cổng CatalogClient với cài đặt HTTP tới catalog-service. */
@Configuration
public class CatalogClientConfiguration {

    @Bean
    CatalogClient catalogClient(ServicesProperties properties) {
        return new HttpCatalogClient(DownstreamRestClients.create(properties.catalogUrl(), properties));
    }
}
