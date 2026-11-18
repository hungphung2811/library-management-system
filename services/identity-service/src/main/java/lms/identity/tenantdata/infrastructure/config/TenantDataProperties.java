package lms.identity.tenantdata.infrastructure.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình xuất dữ liệu tenant ({@code lms.tenant-data.export.*}, ADR-0031). {@code encryptionKey} (biến môi trường
 * TENANT_DATA_ENCRYPTION_KEY, base64 của 32 byte) rỗng thì tính năng tắt (API trả 503 EXPORT_NOT_CONFIGURED); không bao
 * giờ có giá trị mặc định trong repo. {@code sources} khai base-url exporter của từng service; nguồn không khai bị bỏ qua
 * và ghi vào manifest là "chưa cấu hình".
 */
@ConfigurationProperties("lms.tenant-data.export")
public record TenantDataProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("./data/tenant-exports") String dir,
        @DefaultValue("") String encryptionKey,
        @DefaultValue("2147483648") long maxBytes,
        @DefaultValue("PT72H") Duration retention,
        @DefaultValue("PT24H") Duration rateLimit,
        @DefaultValue("PT2H") Duration staleAfter,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT5S") Duration retryBackoff,
        @DefaultValue("PT10M") Duration tokenTtl,
        @DefaultValue("PT10S") Duration pollInterval,
        @DefaultValue("true") boolean recoverOnStart,
        @DefaultValue("PT5S") Duration connectTimeout,
        @DefaultValue("PT2M") Duration readTimeout,
        @DefaultValue("identity-service") String appVersion,
        Map<String, Source> sources) {

    public TenantDataProperties {
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    public record Source(@DefaultValue("") String baseUrl) {
    }
}
