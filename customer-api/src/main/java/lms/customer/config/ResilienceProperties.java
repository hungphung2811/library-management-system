package lms.customer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.resilience.*: bulkhead cho lời gọi sang từng service phía sau. Số lời gọi đồng thời tối đa mỗi đích (identity,
 * system); hết chỗ thì 503 ngay, để một service treo không cạn các thread Tomcat (compose đặt tối đa 20).
 */
@ConfigurationProperties(prefix = "app.resilience")
public record ResilienceProperties(int maxConcurrentCalls) {

    public static final int DEFAULT_MAX_CONCURRENT_CALLS = 8;

    public ResilienceProperties {
        maxConcurrentCalls = maxConcurrentCalls <= 0 ? DEFAULT_MAX_CONCURRENT_CALLS : maxConcurrentCalls;
    }
}
