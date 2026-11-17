package lms.identity.contract.infrastructure.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình {@code lms.contract.*} (ADR-0012 mục 2.4.1): múi giờ nghiệp vụ, ân hạn sau ngày kết thúc, các mốc nhắc
 * hạn (ngày). Khối {@code job.enabled|cron} đọc trực tiếp bằng placeholder ở {@code ContractLifecycleJob}.
 */
@ConfigurationProperties(prefix = "lms.contract")
public record ContractProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") ZoneId zone,
        @DefaultValue("P7D") Duration gracePeriod,
        @DefaultValue({"30", "7", "1"}) List<Integer> reminderDays) {
}
