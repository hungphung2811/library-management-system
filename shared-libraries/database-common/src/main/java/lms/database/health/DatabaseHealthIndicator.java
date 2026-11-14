package lms.database.health;

import lms.database.api.HealthStatus;
import lms.database.core.DatabaseConnectionRegistry;
import lms.database.core.RegisteredConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Gộp health các connection cho Actuator. Chỉ connection critical bị DOWN mới làm tổng DOWN;
 * connection phụ (ví dụ cache) hoặc bị tắt chỉ hiện trong chi tiết.
 */
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DatabaseConnectionRegistry registry;
    private final DatabaseHealthChecker checker;

    public DatabaseHealthIndicator(DatabaseConnectionRegistry registry, DatabaseHealthChecker checker) {
        this.registry = registry;
        this.checker = checker;
    }

    @Override
    public Health health() {
        boolean criticalDown = false;
        Map<String, Object> details = new LinkedHashMap<>();
        for (RegisteredConnection entry : registry.entries()) {
            HealthStatus status = checker.check(entry);
            details.put(entry.name(), status.status() + (status.detail() == null ? "" : " - " + status.detail()));
            if (status.status() == HealthStatus.Status.DOWN && entry.spec().isCritical()) {
                criticalDown = true;
            }
        }
        Health.Builder builder = criticalDown ? Health.down() : Health.up();
        return builder.withDetails(details).build();
    }
}
