package lms.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình wrapper cache. Biến môi trường {@code LMS_CACHE_ENABLED=false} (hoặc {@code lms.cache.enabled=false}) tắt hẳn
 * cache: mọi lời gọi chạy thẳng loader. Dev/demo tắt cache (số liệu luôn mới), môi trường thật để mặc định (bật).
 *
 * @param enabled      bật/tắt cache (mặc định bật)
 * @param keyPrefix    tiền tố mọi khóa Redis, để nhiều ứng dụng dùng chung một Redis
 * @param version      phiên bản khóa: đổi giá trị này để bỏ toàn bộ cache cũ sau khi đổi cấu trúc dữ liệu
 * @param errorBackoff sau lỗi Redis thì bỏ qua Redis trong khoảng này (gọi thẳng loader) để không chịu timeout mỗi request
 * @param maxValueChars giá trị JSON dài hơn mức này thì không ghi vào cache
 */
@ConfigurationProperties(prefix = "lms.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("lms") String keyPrefix,
        @DefaultValue("1") String version,
        @DefaultValue("5s") Duration errorBackoff,
        @DefaultValue("262144") int maxValueChars) {
}
