package lms.system.migration.infrastructure.flyway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lms.system.migration.application.MigrationHistoryPort;
import lms.system.migration.application.MigrationRunnerPort;
import lms.system.migration.domain.MigrationExecutionException;
import lms.system.migration.domain.MigrationScript;
import lms.system.migration.domain.MigrationState;
import lms.system.migration.domain.RunResult;
import lms.system.migration.domain.TargetMigrationStatus;
import lms.system.migration.domain.UnknownTargetException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Adapter Flyway: chạy migrate và đọc lịch sử (flyway_schema_history mặc định của từng DB) qua Flyway API.
 * Không baseline khi DB đã có dữ liệu để tránh che lỗi cấu hình sai DB.
 */
public class FlywayMigrationAdapter implements MigrationRunnerPort, MigrationHistoryPort {

    private final Map<String, DbTarget> targets;

    public FlywayMigrationAdapter(Map<String, DbTarget> targets) {
        this.targets = new LinkedHashMap<>(targets);
    }

    @Override
    public List<String> targets() {
        return List.copyOf(targets.keySet());
    }

    @Override
    public RunResult migrate(String target) {
        Flyway flyway = flyway(target);
        try {
            MigrateResult result = flyway.migrate();
            return new RunResult(target, result.migrationsExecuted, result.targetSchemaVersion);
        } catch (FlywayException e) {
            throw new MigrationExecutionException(target, e.getMessage(), e);
        }
    }

    @Override
    public TargetMigrationStatus status(String target) {
        Flyway flyway = flyway(target);
        try {
            List<MigrationScript> scripts = new ArrayList<>();
            for (MigrationInfo info : flyway.info().all()) {
                scripts.add(toScript(info));
            }
            return new TargetMigrationStatus(target, scripts);
        } catch (FlywayException e) {
            throw new MigrationExecutionException(target, e.getMessage(), e);
        }
    }

    private Flyway flyway(String target) {
        DbTarget t = targets.get(target);
        if (t == null) {
            throw new UnknownTargetException(target);
        }
        return Flyway.configure()
                .dataSource(t.url(), t.username(), t.password())
                .locations(t.locations().toArray(String[]::new))
                .baselineOnMigrate(false)
                .load();
    }

    private static MigrationScript toScript(MigrationInfo info) {
        return new MigrationScript(
                info.getVersion() == null ? null : info.getVersion().getVersion(),
                info.getDescription(),
                info.getScript(),
                info.getType() == null ? null : info.getType().name(),
                info.getChecksum(),
                mapState(info.getState()),
                info.getInstalledOn() == null ? null : Instant.ofEpochMilli(info.getInstalledOn().getTime()),
                info.getInstalledBy(),
                info.getExecutionTime());
    }

    private static MigrationState mapState(org.flywaydb.core.api.MigrationState state) {
        return switch (state) {
            case SUCCESS, OUT_OF_ORDER, OUTDATED, SUPERSEDED, FUTURE_SUCCESS, BASELINE -> MigrationState.APPLIED;
            case PENDING -> MigrationState.PENDING;
            case FAILED, FUTURE_FAILED, MISSING_FAILED -> MigrationState.FAILED;
            case MISSING_SUCCESS -> MigrationState.MISSING;
            case IGNORED -> MigrationState.IGNORED;
            default -> MigrationState.OTHER;
        };
    }
}
