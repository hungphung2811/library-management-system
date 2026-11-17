package lms.api.dashboard;

import java.time.Clock;
import java.time.Duration;
import lms.api.config.ServiceClients;
import lms.api.dashboard.client.CatalogTitleClient;
import lms.api.dashboard.client.DashboardStatsClient;
import lms.api.dashboard.client.PlatformModulesClient;
import lms.api.dashboard.client.http.HttpCatalogTitleClient;
import lms.api.dashboard.client.http.HttpDashboardStatsClient;
import lms.api.dashboard.client.http.HttpPlatformModulesClient;
import lms.api.dashboard.service.DashboardAggregator;
import lms.api.dashboard.service.DashboardExecutor;
import lms.api.dashboard.service.ModuleResolver;
import lms.api.dashboard.service.TitleNameEnricher;
import lms.api.support.TokenRelayInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Bean của nhóm dashboard (ADR-0018 mục 2.2). RestClient riêng, timeout ngắn (connect 1s, read 3s) thay vì
 * {@link ServiceClients#create} dùng chung (2s/5s); hạn chót chung của summary 3s. Có thể ghi đè bằng
 * {@code lms.api.dashboard.connect-timeout|read-timeout|deadline}.
 */
@Configuration
public class DashboardConfiguration {

    private static RestClient client(String baseUrl, Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
    }

    @Bean
    DashboardStatsClient dashboardStatsClient(ServiceClients clients,
                                              @Value("${lms.api.dashboard.connect-timeout:1s}") Duration connect,
                                              @Value("${lms.api.dashboard.read-timeout:3s}") Duration read) {
        var urls = clients.urls();
        return new HttpDashboardStatsClient(client(urls.catalogUrl(), connect, read),
                client(urls.circulationUrl(), connect, read), client(urls.salesUrl(), connect, read));
    }

    @Bean
    CatalogTitleClient dashboardCatalogTitleClient(ServiceClients clients,
                                                   @Value("${lms.api.dashboard.connect-timeout:1s}") Duration connect,
                                                   @Value("${lms.api.dashboard.read-timeout:3s}") Duration read) {
        return new HttpCatalogTitleClient(client(clients.urls().catalogUrl(), connect, read));
    }

    @Bean
    PlatformModulesClient dashboardPlatformModulesClient(ServiceClients clients,
                                                         @Value("${lms.api.dashboard.connect-timeout:1s}") Duration connect,
                                                         @Value("${lms.api.dashboard.read-timeout:3s}") Duration read) {
        return new HttpPlatformModulesClient(client(clients.urls().identityUrl(), connect, read));
    }

    /** Không phải bean kiểu Executor (xem DashboardExecutor). Spring tự gọi destroy() khi tắt. */
    @Bean
    DashboardExecutor dashboardExecutor() {
        return new DashboardExecutor(6, 12, 24);
    }

    @Bean
    ModuleResolver dashboardModuleResolver(PlatformModulesClient platformModules) {
        return new ModuleResolver(platformModules);
    }

    @Bean
    TitleNameEnricher dashboardTitleNameEnricher(CatalogTitleClient titles) {
        return new TitleNameEnricher(titles);
    }

    @Bean
    DashboardAggregator dashboardAggregator(DashboardStatsClient stats, ModuleResolver resolver, TitleNameEnricher titles,
                                            DashboardExecutor executor,
                                            @Value("${lms.api.dashboard.deadline:3s}") Duration deadline) {
        return new DashboardAggregator(stats, resolver, titles, executor, Clock.systemUTC(), deadline);
    }
}
