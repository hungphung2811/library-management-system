package lms.api.identity.imports;

import lms.api.config.ServiceClients;
import lms.api.imports.ImportForwarder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean chuyển tiếp nhập Excel sang identity-service (client riêng, read-timeout dài hơn, ADR-0024). */
@Configuration
public class IdentityImportConfiguration {

    @Bean
    ImportForwarder identityImportForwarder(ServiceClients clients) {
        return new ImportForwarder(clients.createForImport(clients.urls().identityUrl()));
    }
}
