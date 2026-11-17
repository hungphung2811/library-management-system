package lms.customer.site;

import io.micrometer.core.instrument.MeterRegistry;
import lms.customer.config.CmsProperties;
import lms.customer.config.ServiceClients;
import lms.customer.site.client.CachedSiteClient;
import lms.customer.site.client.SiteClient;
import lms.customer.site.client.http.HttpSiteClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SiteClientConfiguration {

    @Bean
    SiteClient siteClient(ServiceClients clients, CmsProperties cms, ObjectProvider<MeterRegistry> metrics) {
        CachedSiteClient cached = new CachedSiteClient(
                new HttpSiteClient(clients.create(ServiceClients.SYSTEM, clients.urls().systemUrl())),
                cms.cacheTtl(), cms.cacheMaxSize());
        metrics.ifAvailable(cached::bindMetrics);
        return cached;
    }
}
