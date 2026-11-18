package lms.customer.shop;

import lms.customer.config.ServiceClients;
import lms.customer.shop.client.ShopInvoiceClient;
import lms.customer.shop.client.http.HttpShopInvoiceClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client yêu cầu xuất hóa đơn của khách (ADR-0030 gói A): cùng sales-service như {@link ShopClientConfiguration}. */
@Configuration
public class ShopInvoiceClientConfiguration {

    @Bean
    ShopInvoiceClient shopInvoiceClient(ServiceClients clients) {
        return new HttpShopInvoiceClient(clients.create(ServiceClients.SALES, clients.urls().salesUrl()));
    }
}
