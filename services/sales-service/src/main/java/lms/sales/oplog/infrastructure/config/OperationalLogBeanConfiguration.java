package lms.sales.oplog.infrastructure.config;

import ch.qos.logback.classic.Level;
import java.time.Clock;
import lms.sales.oplog.application.OperationalLogQueryUseCase;
import lms.sales.oplog.application.OperationalLogStore;
import lms.sales.oplog.application.PurgeOperationalLogsUseCase;
import lms.sales.oplog.infrastructure.logging.OperationalLogAppenderRegistrar;
import lms.sales.oplog.infrastructure.logging.OperationalLogWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition root của context oplog (log vận hành lưu DB, ADR-0029): use case đọc/dọn luôn có; bộ thu thập
 * (appender + luồng ghi) chỉ có khi {@code lms.sales.logs.persist.enabled} (mặc định true; tắt ở demo-seed và test);
 * job dọn chỉ có khi {@code lms.sales.logs.purge.enabled} (mặc định true).
 */
@Configuration
public class OperationalLogBeanConfiguration {

    @Bean
    OperationalLogQueryUseCase operationalLogQueryUseCase(OperationalLogStore store, Clock clock) {
        return new OperationalLogQueryUseCase(store, clock);
    }

    @Bean
    PurgeOperationalLogsUseCase purgeOperationalLogsUseCase(OperationalLogStore store, Clock clock,
            @Value("${lms.sales.logs.retention-days:30}") int retentionDays,
            @Value("${lms.sales.logs.purge.batch-size:1000}") int batchSize) {
        return new PurgeOperationalLogsUseCase(store, clock, retentionDays, batchSize);
    }

    /** Thu thập log: writer + appender gắn vào ba logger kênh; tháo appender khi context đóng. */
    @Configuration
    @ConditionalOnProperty(name = "lms.sales.logs.persist.enabled", havingValue = "true", matchIfMissing = true)
    static class Collector {

        @Bean(initMethod = "start", destroyMethod = "stop")
        OperationalLogWriter operationalLogWriter(OperationalLogStore store,
                @Value("${lms.sales.logs.persist.queue-capacity:5000}") int capacity,
                @Value("${lms.sales.logs.persist.batch-size:200}") int batchSize) {
            return new OperationalLogWriter(store, capacity, batchSize);
        }

        @Bean
        OperationalLogAppenderRegistrar operationalLogAppenderRegistrar(OperationalLogWriter writer,
                @Value("${lms.sales.logs.persist.system-min-level:WARN}") String systemMinLevel) {
            return new OperationalLogAppenderRegistrar(writer, Level.toLevel(systemMinLevel, Level.WARN));
        }
    }

    /** Job dọn log quá hạn giữ. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "lms.sales.logs.purge.enabled", havingValue = "true", matchIfMissing = true)
    static class Purge {

        @Bean
        OperationalLogPurgeJob operationalLogPurgeJob(PurgeOperationalLogsUseCase useCase) {
            return new OperationalLogPurgeJob(useCase);
        }
    }
}
