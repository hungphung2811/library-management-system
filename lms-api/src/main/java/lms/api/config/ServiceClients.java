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
    private final ImportClientProperties importProperties;

    public ServiceClients(ServicesProperties properties) {
        this(properties, new ImportClientProperties(null));
    }

    public ServiceClients(ServicesProperties properties, ImportClientProperties importProperties) {
        this.properties = properties;
        this.importProperties = importProperties;
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

    /** Như {@link #create} nhưng read-timeout riêng cho lời gọi nhập Excel (app.imports.read-timeout, mặc định 30 s). */
    public RestClient createForImport(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(importProperties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
    }
}
