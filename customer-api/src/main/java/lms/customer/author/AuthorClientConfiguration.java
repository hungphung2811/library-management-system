package lms.customer.author;

import lms.customer.author.client.AuthorCatalogClient;
import lms.customer.author.client.TopSoldClient;
import lms.customer.author.client.http.HttpAuthorCatalogClient;
import lms.customer.author.client.http.HttpTopSoldClient;
import lms.customer.config.ServiceClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthorClientConfiguration {

    @Bean
    AuthorCatalogClient authorCatalogClient(ServiceClients clients) {
        return new HttpAuthorCatalogClient(clients.create(ServiceClients.CATALOG, clients.urls().catalogUrl()));
    }

    @Bean
    TopSoldClient topSoldClient(ServiceClients clients) {
        return new HttpTopSoldClient(clients.create(ServiceClients.SALES, clients.urls().salesUrl()));
    }

    @Bean
    FeaturedAuthors featuredAuthors(AuthorCatalogClient catalog, TopSoldClient sales) {
        return new FeaturedAuthors(catalog, sales);
    }
}
