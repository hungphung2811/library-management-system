package lms.database.health;

import lms.database.api.HealthStatus;
import lms.database.api.exception.ConnectionNotFoundException;
import lms.database.core.DatabaseConnectionRegistry;
import lms.database.core.RegisteredConnection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Kiểm tra sức khỏe các connection trong registry. DISABLED không phải lỗi; FAILED coi như DOWN. */
public class DatabaseHealthChecker {

    private final DatabaseConnectionRegistry registry;

    public DatabaseHealthChecker(DatabaseConnectionRegistry registry) {
        this.registry = registry;
    }

    public HealthStatus check(String name) {
        RegisteredConnection entry = registry.entry(name)
                .orElseThrow(() -> new ConnectionNotFoundException(name, registry.names()));
        return check(entry);
    }

    public HealthStatus check(RegisteredConnection entry) {
        switch (entry.state()) {
            case DISABLED:
                return HealthStatus.disabled();
            case FAILED:
                return HealthStatus.down("Khởi tạo thất bại: " + entry.detail());
            default:
                try {
                    return entry.connection().health();
                } catch (RuntimeException e) {
                    return HealthStatus.down(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
        }
    }

    public Map<String, HealthStatus> checkAll() {
        Map<String, HealthStatus> result = new LinkedHashMap<>();
        registry.entries().forEach(entry -> result.put(entry.name(), check(entry)));
        return result;
    }
}
