package lms.integration.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient gọi service nội bộ: JDK HttpClient dùng chung (HTTP/1.1, không theo redirect, connect timeout, tái dùng kết
 * nối), read timeout hữu hạn, correlation id. Bulkhead/metric theo đích là việc của pha I3.
 */
@Configuration
@EnableConfigurationProperties(ServicesProperties.class)
public class RestClientConfiguration {

    @Bean
    RestClient identityRestClient(ServicesProperties properties) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(properties.identityUrl()).requestFactory(factory)
                .requestInterceptor(new CorrelationIdInterceptor()).build();
    }
}
