package lms.customer.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ServicesProperties.class, StorefrontProperties.class, CmsProperties.class,
        ResilienceProperties.class})
public class RestClientConfiguration {

    @Bean
    ServiceClients serviceClients(ServicesProperties properties, ResilienceProperties resilience,
                                  ObjectProvider<MeterRegistry> registry) {
        return new ServiceClients(properties, resilience, registry.getIfAvailable(SimpleMeterRegistry::new));
    }
}
