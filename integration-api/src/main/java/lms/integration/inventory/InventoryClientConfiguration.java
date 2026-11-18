package lms.integration.inventory;

import lms.integration.config.DownstreamRestClients;
import lms.integration.config.ServicesProperties;
import lms.integration.inventory.client.InventoryClient;
import lms.integration.inventory.client.http.HttpInventoryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Nối cổng InventoryClient với cài đặt HTTP tới sales-service. */
@Configuration
public class InventoryClientConfiguration {

    @Bean
    InventoryClient inventoryClient(ServicesProperties properties) {
        return new HttpInventoryClient(DownstreamRestClients.create(properties.salesUrl(), properties));
    }
}
