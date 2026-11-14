package lms.migration.infrastructure.config;

import lms.migration.application.RunMigrationsUseCase;
import lms.migration.domain.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Chạy migration mọi target ngay sau khi các singleton được tạo xong (trước khi web server khởi động) nên
 * health chỉ UP sau khi migrate xong. Lỗi thì ném ra làm context fail (fail-fast) trừ khi migration.fail-fast=false.
 */
class MigrationStartupRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(MigrationStartupRunner.class);

    private final RunMigrationsUseCase runMigrations;
    private final MigrationProperties props;

    MigrationStartupRunner(RunMigrationsUseCase runMigrations, MigrationProperties props) {
        this.runMigrations = runMigrations;
        this.props = props;
    }

    @Override
    public void afterSingletonsInstantiated() {
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
