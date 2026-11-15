package lms.system.migration.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lms.system.migration.application.GetMigrationStatusUseCase;
import lms.system.migration.application.MigrationHistoryPort;
import lms.system.migration.application.MigrationRunnerPort;
import lms.system.migration.application.RunMigrationsUseCase;
import lms.system.migration.infrastructure.flyway.DbTarget;
import lms.system.migration.infrastructure.flyway.FlywayMigrationAdapter;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
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

    /** Chạy migration trong giai đoạn khởi tạo bean, trước EntityManagerFactory (xem bean bên dưới). */
    @Bean
    MigrationInitializer migrationInitializer(RunMigrationsUseCase runMigrations, MigrationProperties props) {
        return new MigrationInitializer(runMigrations, props);
    }

    /**
     * EntityManagerFactory (ddl-auto: validate trên system_db) phải tạo SAU migration, nếu không DB trống làm khởi
     * động hỏng. Flyway autoconfig của Boot bị tắt nên phải khai báo phụ thuộc này thủ công.
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor migrationBeforeEntityManagerFactory() {
        return new EntityManagerFactoryDependsOnPostProcessor("migrationInitializer");
    }
}
