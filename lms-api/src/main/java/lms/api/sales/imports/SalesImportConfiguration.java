package lms.api.sales.imports;

import lms.api.config.ServiceClients;
import lms.api.imports.ImportForwarder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean chuyển tiếp nhập Excel sang sales-service (client riêng, read-timeout dài hơn, ADR-0024). */
@Configuration
public class SalesImportConfiguration {

    @Bean
    ImportForwarder salesImportForwarder(ServiceClients clients) {
        return new ImportForwarder(clients.createForImport(clients.urls().salesUrl()));
    }
}
