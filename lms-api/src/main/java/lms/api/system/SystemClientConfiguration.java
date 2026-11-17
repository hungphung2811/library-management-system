package lms.api.system;

import lms.api.config.ServiceClients;
import lms.api.system.client.CmsClient;
import lms.api.system.client.SystemClient;
import lms.api.system.client.http.HttpCmsClient;
import lms.api.system.client.http.HttpSystemClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SystemClientConfiguration {

    @Bean
    SystemClient systemClient(ServiceClients clients) {
        return new HttpSystemClient(clients.create(clients.urls().systemUrl()));
    }

    @Bean
    CmsClient cmsClient(ServiceClients clients) {
        return new HttpCmsClient(clients.create(clients.urls().systemUrl()));
    }
}
