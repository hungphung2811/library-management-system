package lms.api.catalog.imports;

import lms.api.config.ServiceClients;
import lms.api.imports.ImportForwarder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean chuyển tiếp nhập Excel sang catalog-service (client riêng, read-timeout dài hơn, ADR-0024). */
@Configuration
public class CatalogImportConfiguration {

    @Bean
    ImportForwarder catalogImportForwarder(ServiceClients clients) {
        return new ImportForwarder(clients.createForImport(clients.urls().catalogUrl()));
    }
}
