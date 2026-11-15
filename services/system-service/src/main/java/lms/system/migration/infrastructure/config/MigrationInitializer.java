package lms.system.migration.infrastructure.config;

import lms.system.migration.application.RunMigrationsUseCase;
import lms.system.migration.domain.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Chạy migration mọi target (gồm system_db) trong giai đoạn khởi tạo bean. EntityManagerFactory khai báo phụ thuộc
 * bean này (xem {@link MigrationBeanConfiguration}) nên migrate xong rồi Hibernate mới validate schema, DB trống
 * vẫn khởi động được; web server chỉ nhận request sau đó nên health UP sau khi migrate xong. Lỗi thì ném ra làm
 * context fail (fail-fast) trừ khi migration.fail-fast=false.
 */
class MigrationInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MigrationInitializer.class);

    private final RunMigrationsUseCase runMigrations;
    private final MigrationProperties props;

    MigrationInitializer(RunMigrationsUseCase runMigrations, MigrationProperties props) {
        this.runMigrations = runMigrations;
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        if (!props.runOnStartup()) {
            log.info("migration.run-on-startup=false: bỏ qua migration lúc khởi động");
            return;
        }
        try {
            for (RunResult r : runMigrations.runAll()) {
                log.info("Migration target '{}': đã chạy {} script, schema version {}", r.target(), r.executed(),
                        r.schemaVersion());
            }
        } catch (RuntimeException e) {
            if (props.failFast()) {
                throw e;
            }
            log.error("Migration lỗi nhưng migration.fail-fast=false nên tiếp tục khởi động", e);
        }
    }
}
