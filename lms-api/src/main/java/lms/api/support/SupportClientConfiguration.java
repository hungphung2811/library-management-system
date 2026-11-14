package lms.api.support;

import lms.api.config.ServiceClients;
import lms.api.support.client.SupportClient;
import lms.api.support.client.http.HttpSupportClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupportClientConfiguration {

    @Bean
    SupportClient supportClient(ServiceClients clients) {
        return new HttpSupportClient(clients.create(clients.urls().supportUrl()));
    }
}
