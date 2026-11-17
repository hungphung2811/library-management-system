package lms.api.reports;

import java.time.Duration;
import lms.api.config.ServiceClients;
import lms.api.reports.client.ReportClient;
import lms.api.reports.client.http.HttpReportClient;
import lms.api.support.TokenRelayInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient riêng cho báo cáo (ADR-0018 mục 2.2): connect 2s, read 30s (khớp proxy_read_timeout 30s của nginx),
 * thay cho timeout 2s/5s dùng chung. Ghi đè bằng {@code lms.api.reports.connect-timeout|read-timeout}.
 */
@Configuration
public class ReportsConfiguration {

    @Bean
    ReportClient reportClient(ServiceClients clients,
                              @Value("${lms.api.reports.connect-timeout:2s}") Duration connect,
                              @Value("${lms.api.reports.read-timeout:30s}") Duration read) {
        return new HttpReportClient(client(clients.urls().circulationUrl(), connect, read),
                client(clients.urls().salesUrl(), connect, read));
    }

    private static RestClient client(String baseUrl, Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
    }
}
