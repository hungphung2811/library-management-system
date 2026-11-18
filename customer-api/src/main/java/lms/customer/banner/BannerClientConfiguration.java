package lms.customer.banner;

import lms.customer.banner.client.BannerClient;
import lms.customer.banner.client.http.HttpBannerClient;
import lms.customer.config.ServiceClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BannerClientConfiguration {

    @Bean
    BannerClient bannerClient(ServiceClients clients) {
        return new HttpBannerClient(clients.create(ServiceClients.SALES, clients.urls().salesUrl()));
    }

    @Bean
    BannerCache bannerCache() {
        return new BannerCache();
    }
}
