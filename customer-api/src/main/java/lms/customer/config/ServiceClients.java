package lms.customer.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Nhà máy RestClient gọi service nội bộ: JDK HttpClient dùng chung (HTTP/1.1, không theo redirect, connect timeout,
 * tái dùng kết nối), read timeout, correlation id, bulkhead + retry GET + metric theo đích
 * ({@link DownstreamCallInterceptor}). Mọi client cùng đích (identity cho cả đăng nhập và cấp token storefront) dùng
 * chung một bulkhead.
 */
public class ServiceClients {

    public static final String IDENTITY = "identity";
    public static final String SYSTEM = "system";
    public static final String SALES = "sales";
    public static final String CIRCULATION = "circulation";
    public static final String CATALOG = "catalog";

    private final ServicesProperties properties;
    private final ResilienceProperties resilience;
    private final MeterRegistry registry;
    private final HttpClient httpClient;
    private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

    public ServiceClients(ServicesProperties properties, ResilienceProperties resilience, MeterRegistry registry) {
        this.properties = properties;
        this.resilience = resilience;
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(properties.connectTimeout()).build();
    }

    public ServicesProperties urls() {
        return properties;
    }

    /** @param target tên đích thuộc tập đóng (identity, system), dùng làm tag metric. */
    public RestClient create(String target, String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        Semaphore permits = bulkheads.computeIfAbsent(target, t -> new Semaphore(resilience.maxConcurrentCalls()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new CorrelationIdInterceptor())
                .requestInterceptor(new DownstreamCallInterceptor(target, permits, registry)).build();
    }
}
