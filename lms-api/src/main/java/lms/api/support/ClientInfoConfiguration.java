package lms.api.support;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean xác định IP/user-agent của client (lms.api.client-ip.trusted-proxies). */
@Configuration
@EnableConfigurationProperties(ClientIpProperties.class)
public class ClientInfoConfiguration {

    @Bean
    ClientInfoResolver clientInfoResolver(ClientIpProperties properties) {
        return new ClientInfoResolver(properties);
    }
}
