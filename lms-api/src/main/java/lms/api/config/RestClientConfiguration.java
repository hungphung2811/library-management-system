package lms.api.config;

import lms.api.support.TokenRelayInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** RestClient cho từng service nội bộ: có timeout và tự chuyển tiếp token của người dùng và correlation id. */
@Configuration
@EnableConfigurationProperties(ServicesProperties.class)
public class RestClientConfiguration {

    private static RestClient build(String baseUrl, ServicesProperties p) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(p.connectTimeout());
        factory.setReadTimeout(p.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
    }

    @Bean
    RestClient catalogRestClient(ServicesProperties properties) {
        return build(properties.catalogUrl(), properties);
    }
}
