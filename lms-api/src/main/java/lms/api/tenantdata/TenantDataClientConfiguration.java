package lms.api.tenantdata;

import lms.api.config.ServiceClients;
import lms.api.tenantdata.client.TenantDataClient;
import lms.api.tenantdata.client.http.HttpTenantDataClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client xuất dữ liệu tenant (ADR-0031 F2): identity-service, đường dẫn /internal/tenant-data/exports/**. */
@Configuration
public class TenantDataClientConfiguration {

    @Bean
    TenantDataClient tenantDataClient(ServiceClients clients) {
        return new HttpTenantDataClient(clients.create(clients.urls().identityUrl()));
    }
}
