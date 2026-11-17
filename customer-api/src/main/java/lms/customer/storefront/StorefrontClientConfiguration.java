package lms.customer.storefront;

import io.micrometer.core.instrument.MeterRegistry;
import lms.customer.config.ServiceClients;
import lms.customer.config.StorefrontProperties;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.client.http.HttpStorefrontAccessClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorefrontClientConfiguration {

    @Bean
    StorefrontAccessClient storefrontAccessClient(ServiceClients clients, StorefrontProperties properties) {
        return new HttpStorefrontAccessClient(clients.create(ServiceClients.IDENTITY, clients.urls().identityUrl()), properties.clientSecret());
    }

    @Bean
    StorefrontAccessCache storefrontAccessCache(StorefrontAccessClient client, StorefrontProperties properties,
                                                ObjectProvider<MeterRegistry> metrics) {
        StorefrontAccessCache cache = new StorefrontAccessCache(client, properties.cacheMaxSize(),
                properties.negativeTtl(), properties.refreshMargin(), properties.originTtl(),
                com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        metrics.ifAvailable(cache::bindMetrics);
        return cache;
    }

    @Bean
    ShopSessionGuard shopSessionGuard() {
        return new ShopSessionGuard();
    }
}
