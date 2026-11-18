package lms.identity.apiclient.infrastructure.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationUsagePort;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Đọc khóa {@code lms:usage:v1:t:{tenant}:a:{app}:{yyyyMMdd}} (integration-api INCR, TTL 35 ngày) bằng một MGET. Khóa
 * chỉ gồm UUID và ngày đã parse. Redis lỗi thì USAGE_UNAVAILABLE (503 chung), không trả số 0 giả.
 */
@Component
public class RedisApplicationUsageAdapter implements ApplicationUsagePort {

    private static final Logger log = LoggerFactory.getLogger(RedisApplicationUsageAdapter.class);

    private final StringRedisTemplate redis;

    public RedisApplicationUsageAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Map<LocalDate, Long> dailyRequests(UUID tenantId, UUID applicationId, List<LocalDate> days) {
        List<String> keys = days.stream().map(d -> "lms:usage:v1:t:" + tenantId + ":a:" + applicationId + ":"
                + d.format(DateTimeFormatter.BASIC_ISO_DATE)).toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            log.warn("Không đọc được bộ đếm sử dụng ({})", e.getClass().getSimpleName());
            throw unavailable();
        }
        if (values == null || values.size() != keys.size()) throw unavailable();
        Map<LocalDate, Long> out = new HashMap<>();
        for (int i = 0; i < days.size(); i++) {
            String v = values.get(i);
            if (v == null) continue;
            try {
                out.put(days.get(i), Long.parseLong(v));
            } catch (NumberFormatException e) {
                // Giá trị lạ không được làm hỏng cả báo cáo: coi như không có số liệu của ngày đó.
                log.warn("Bộ đếm sử dụng có giá trị không phải số, bỏ qua một ngày");
            }
        }
        return out;
    }

    private static ApplicationException unavailable() {
        return new ApplicationException(Reason.USAGE_UNAVAILABLE, "Thống kê tạm thời không khả dụng");
    }
}
