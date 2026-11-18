package lms.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ServicesProperties.class, ImportClientProperties.class})
public class RestClientConfiguration {

    @Bean
    ServiceClients serviceClients(ServicesProperties properties, ImportClientProperties importProperties) {
        return new ServiceClients(properties, importProperties);
    }
}
