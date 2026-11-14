package lms.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ServicesProperties.class)
public class RestClientConfiguration {

    @Bean
    ServiceClients serviceClients(ServicesProperties properties) {
        return new ServiceClients(properties);
    }
}
