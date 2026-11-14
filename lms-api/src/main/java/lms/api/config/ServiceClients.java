package lms.api.config;

import lms.api.support.TokenRelayInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Nhà máy tạo RestClient gọi service nội bộ: có timeout, tự chuyển tiếp token của người dùng và correlation id.
 * Mỗi context tự khai báo client của mình (xem catalog/CatalogClientConfiguration) mà không phải sửa file dùng chung.
 */
public class ServiceClients {

    private final ServicesProperties properties;

    public ServiceClients(ServicesProperties properties) {
        this.properties = properties;
    }

    public ServicesProperties urls() {
        return properties;
    }

    public RestClient create(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
    }
}
