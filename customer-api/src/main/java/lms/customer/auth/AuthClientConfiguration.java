package lms.customer.auth;

import lms.customer.auth.client.AuthClient;
import lms.customer.auth.client.http.HttpAuthClient;
import lms.customer.config.ServiceClients;
import lms.customer.config.StorefrontProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthClientConfiguration {

    @Bean
    AuthClient authClient(ServiceClients clients, StorefrontProperties storefront) {
        return new HttpAuthClient(clients.create(ServiceClients.IDENTITY, clients.urls().identityUrl()),
                storefront.refreshTenantIdEnabled());
    }
}
