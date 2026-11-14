package lms.migration.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lms.migration.application.GetMigrationStatusUseCase;
import lms.migration.application.MigrationHistoryPort;
import lms.migration.application.MigrationRunnerPort;
import lms.migration.application.RunMigrationsUseCase;
import lms.migration.infrastructure.flyway.DbTarget;
import lms.migration.infrastructure.flyway.FlywayMigrationAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationBeanConfiguration {

    @Bean
    FlywayMigrationAdapter flywayMigrationAdapter(MigrationProperties props) {
        Map<String, DbTarget> targets = new LinkedHashMap<>();
        props.targets().forEach((name, t) -> targets.put(name, new DbTarget(t.url(), t.username(), t.password(),
                t.locations() == null || t.locations().isEmpty()
                        ? List.of("classpath:db/migration/" + name) : t.locations())));
        return new FlywayMigrationAdapter(targets);
    }

    @Bean
    RunMigrationsUseCase runMigrationsUseCase(MigrationRunnerPort runner, MigrationHistoryPort history) {
        return new RunMigrationsUseCase(runner, history);
    }

    @Bean
    GetMigrationStatusUseCase getMigrationStatusUseCase(MigrationHistoryPort history) {
        return new GetMigrationStatusUseCase(history);
    }

    /** Chạy migration trong giai đoạn khởi tạo context, trước khi web server nhận request. */
    @Bean
    MigrationStartupRunner migrationStartupRunner(RunMigrationsUseCase runMigrations, MigrationProperties props) {
        return new MigrationStartupRunner(runMigrations, props);
    }
}
