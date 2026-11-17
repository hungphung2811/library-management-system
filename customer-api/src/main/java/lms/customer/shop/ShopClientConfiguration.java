package lms.customer.shop;

import lms.customer.config.ServiceClients;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.client.http.HttpShopClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShopClientConfiguration {

    @Bean
    ShopClient shopClient(ServiceClients clients) {
        return new HttpShopClient(clients.create(ServiceClients.SALES, clients.urls().salesUrl()));
    }
}
