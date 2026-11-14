package lms.database.core;

import lms.database.api.ConnectionSpec;
import lms.database.api.ConnectionState;
import lms.database.api.DatabaseConnection;
import lms.database.api.DatabaseProvider;
import lms.database.api.DatabaseType;
import lms.database.api.HealthStatus;
import lms.database.api.exception.ConnectionInitializationException;
import lms.database.api.exception.DuplicateProviderException;
import lms.database.api.exception.RetryExhaustedException;
import lms.database.api.exception.UnsupportedDatabaseTypeException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mở, tra cứu và đóng connection. Chọn provider theo type qua map dựng từ danh sách bean
 * DatabaseProvider, nên thêm loại DB mới không phải sửa lớp này.
 */
public class DatabaseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionManager.class);

    private final Map<DatabaseType, DatabaseProvider<?>> providers = new EnumMap<>(DatabaseType.class);
    private final DatabaseConnectionRegistry registry;
    private final RetryExecutor retryExecutor;

    public DatabaseConnectionManager(List<DatabaseProvider<?>> providers, DatabaseConnectionRegistry registry,
                                     RetryExecutor retryExecutor) {
        for (DatabaseProvider<?> provider : providers) {
            if (this.providers.putIfAbsent(provider.type(), provider) != null) {
                throw new DuplicateProviderException(provider.type());
            }
        }
        this.registry = registry;
        this.retryExecutor = retryExecutor;
    }

    public Set<DatabaseType> supportedTypes() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    /** Mở lần lượt các connection theo thứ tự cấu hình. */
    public void initializeConnections(Map<String, ConnectionSpec> specs, boolean failOnStartupError) {
        specs.forEach((name, spec) -> open(name, spec, failOnStartupError));
    }

    public void open(String name, ConnectionSpec spec, boolean failOnStartupError) {
        if (!spec.isEnabled()) {
            registry.registerDisabled(name, spec);
            log.info("Connection {} bị tắt, bỏ qua", name);
            return;
        }
        DatabaseProvider<?> provider = providers.get(spec.getType());
        if (provider == null) {
            throw new UnsupportedDatabaseTypeException(spec.getType());
        }
        try {
            DatabaseConnection connection = retryExecutor.execute("Mở connection " + name, spec.getRetry(),
                    () -> provider.create(name, spec));
            registry.register(name, spec, connection);
            log.info("Đã mở connection {} ({})", name, spec.getType());
        } catch (RetryExhaustedException e) {
            // Connection phụ (critical=false) lỗi thì không chặn khởi động, chỉ ghi nhận FAILED.
            if (failOnStartupError && spec.isCritical()) {
                throw new ConnectionInitializationException(name, e);
            }
            log.error("Không mở được connection {}, đánh dấu FAILED: {}", name, e.getMessage());
            registry.registerFailed(name, spec, e);
        }
    }

    /** Đăng ký thủ công một connection đã tạo sẵn. */
    public void registerConnection(String name, ConnectionSpec spec, DatabaseConnection connection) {
        registry.register(name, spec, connection);
    }

    public DatabaseConnection getConnection(String name) {
        return registry.require(name, DatabaseConnection.class);
    }

    public ConnectionState getConnectionStatus(String name) {
        return registry.state(name);
    }

    public HealthStatus healthCheck(String name) {
        return registry.require(name, DatabaseConnection.class).health();
    }

    public void closeConnection(String name) {
        registry.remove(name).ifPresent(entry -> closeQuietly(entry));
    }

    /** Đóng ngược thứ tự mở; lỗi của một connection không chặn các connection còn lại. */
    public void closeAllConnections() {
        List<String> names = new ArrayList<>(registry.names());
        Collections.reverse(names);
        names.forEach(this::closeConnection);
    }

    private void closeQuietly(RegisteredConnection entry) {
        if (entry.connection() == null) {
            return;
        }
        try {
            entry.connection().close();
            log.info("Đã đóng connection {}", entry.name());
        } catch (RuntimeException e) {
            log.error("Lỗi khi đóng connection {}: {}", entry.name(), e.getMessage());
        }
    }
}
