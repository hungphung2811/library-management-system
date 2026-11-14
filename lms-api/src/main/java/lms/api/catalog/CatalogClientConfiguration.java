package lms.api.catalog;

import lms.api.catalog.client.CatalogClient;
import lms.api.catalog.client.http.HttpCatalogClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CatalogClientConfiguration {

    @Bean
    CatalogClient catalogClient(@Qualifier("catalogRestClient") RestClient restClient) {
        return new HttpCatalogClient(restClient);
    }
}
