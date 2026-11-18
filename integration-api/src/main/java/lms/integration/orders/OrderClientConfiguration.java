package lms.integration.orders;

import lms.integration.config.DownstreamRestClients;
import lms.integration.config.ServicesProperties;
import lms.integration.orders.client.OrderClient;
import lms.integration.orders.client.http.HttpOrderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Nối cổng OrderClient với cài đặt HTTP tới sales-service. */
@Configuration
public class OrderClientConfiguration {

    @Bean
    OrderClient orderClient(ServicesProperties properties) {
        return new HttpOrderClient(DownstreamRestClients.create(properties.salesUrl(), properties));
    }
}
