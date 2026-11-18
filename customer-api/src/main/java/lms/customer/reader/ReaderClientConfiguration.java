package lms.customer.reader;

import lms.customer.config.ServiceClients;
import lms.customer.reader.client.CatalogTitleClient;
import lms.customer.reader.client.CirculationReaderClient;
import lms.customer.reader.client.IdentityReaderClient;
import lms.customer.reader.client.http.HttpCatalogTitleClient;
import lms.customer.reader.client.http.HttpCirculationReaderClient;
import lms.customer.reader.client.http.HttpIdentityReaderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReaderClientConfiguration {

    @Bean
    CirculationReaderClient circulationReaderClient(ServiceClients clients) {
        return new HttpCirculationReaderClient(
                clients.create(ServiceClients.CIRCULATION, clients.urls().circulationUrl()));
    }

    @Bean
    IdentityReaderClient identityReaderClient(ServiceClients clients) {
        return new HttpIdentityReaderClient(clients.create(ServiceClients.IDENTITY, clients.urls().identityUrl()));
    }

    @Bean
    CatalogTitleClient catalogTitleClient(ServiceClients clients) {
        return new HttpCatalogTitleClient(clients.create(ServiceClients.CATALOG, clients.urls().catalogUrl()));
    }
}
