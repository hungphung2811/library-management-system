package lms.database.config;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseType;
import lms.database.api.exception.InvalidDatabaseConfigurationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Kiểm tra cấu hình khi khởi động, gom toàn bộ lỗi rồi ném một lần (fail-fast). */
public class DatabasePropertiesValidator {

    private static final Map<DatabaseType, String> JDBC_PREFIX = new EnumMap<>(DatabaseType.class);

    static {
        JDBC_PREFIX.put(DatabaseType.POSTGRESQL, "jdbc:postgresql:");
        JDBC_PREFIX.put(DatabaseType.ORACLE, "jdbc:oracle:");
    }

    public void validate(Map<String, ConnectionSpec> connections, Set<DatabaseType> supportedTypes) {
        List<String> errors = new ArrayList<>();
        Map<String, String> normalizedNames = new HashMap<>();

        connections.forEach((rawName, spec) -> {
            if (rawName == null || rawName.isBlank()) {
                errors.add("Tên connection không được trống");
                return;
            }
            String previous = normalizedNames.put(rawName.trim().toUpperCase(Locale.ROOT), rawName);
            if (previous != null) {
                errors.add("[" + rawName + "] trùng tên với [" + previous + "] (không phân biệt hoa thường)");
            }
            if (spec == null) {
                errors.add("[" + rawName + "] thiếu cấu hình");
                return;
            }
            validateSpec(rawName, spec, supportedTypes, errors);
        });

        if (!errors.isEmpty()) {
            throw new InvalidDatabaseConfigurationException(errors);
        }
    }

    private void validateSpec(String name, ConnectionSpec spec, Set<DatabaseType> supported, List<String> errors) {
        String prefix = "[" + name + "] ";
        if (spec.getType() == null) {
            errors.add(prefix + "thiếu type");
            return;
        }
        if (!supported.contains(spec.getType())) {
            errors.add(prefix + "chưa có provider cho type " + spec.getType()
                    + " (thiếu driver/starter trên classpath?)");
            return;
        }
        // Connection bị tắt chỉ cần type hợp lệ, không mở nên không kiểm tra thêm.
        if (!spec.isEnabled()) {
            return;
        }
        if (spec.getType().isRelational()) {
            validateRelational(prefix, spec, errors);
            validatePool(prefix, spec, errors);
        } else if (spec.getType() == DatabaseType.MONGODB) {
            validateMongo(prefix, spec, errors);
            validatePool(prefix, spec, errors);
        } else if (spec.getType() == DatabaseType.REDIS) {
            validateRedis(prefix, spec, errors);
        }
        validateTimeoutAndRetry(prefix, spec, errors);
        checkPlaceholders(prefix, spec, errors);
    }

    private void validateRelational(String prefix, ConnectionSpec spec, List<String> errors) {
        String expected = JDBC_PREFIX.getOrDefault(spec.getType(), "jdbc:");
        if (isBlank(spec.getUrl())) {
            errors.add(prefix + "thiếu url");
        } else if (!spec.getUrl().startsWith(expected)) {
            errors.add(prefix + "url phải bắt đầu bằng " + expected);
        }
        if (isBlank(spec.getUsername())) {
            errors.add(prefix + "thiếu username");
        }
    }

    private void validateMongo(String prefix, ConnectionSpec spec, List<String> errors) {
        if (isBlank(spec.getUri())) {
            errors.add(prefix + "thiếu uri");
        } else if (!spec.getUri().startsWith("mongodb://") && !spec.getUri().startsWith("mongodb+srv://")) {
            errors.add(prefix + "uri phải bắt đầu bằng mongodb:// hoặc mongodb+srv://");
        }
    }

    private void validateRedis(String prefix, ConnectionSpec spec, List<String> errors) {
        if (isBlank(spec.getHost())) {
            errors.add(prefix + "thiếu host");
        }
        Integer port = spec.getPort();
        if (port != null && (port < 1 || port > 65535)) {
            errors.add(prefix + "port phải trong khoảng 1-65535");
        }
        if (!isBlank(spec.getDatabase())) {
            try {
                if (Integer.parseInt(spec.getDatabase().trim()) < 0) {
                    errors.add(prefix + "database (chỉ số Redis) không được âm");
                }
            } catch (NumberFormatException e) {
                errors.add(prefix + "database của Redis phải là số");
            }
        }
    }

    private void validatePool(String prefix, ConnectionSpec spec, List<String> errors) {
        var pool = spec.getPool();
        if (pool.getMaxSize() < 1) {
            errors.add(prefix + "pool.max-size phải >= 1");
        }
        if (pool.getMinIdle() < 0 || pool.getMinIdle() > pool.getMaxSize()) {
            errors.add(prefix + "pool.min-idle phải trong khoảng 0..max-size");
        }
        if (!positive(pool.getIdleTimeout())) {
            errors.add(prefix + "pool.idle-timeout phải > 0");
        }
        if (!positive(pool.getMaxLifetime())) {
            errors.add(prefix + "pool.max-lifetime phải > 0");
        }
    }

    private void validateTimeoutAndRetry(String prefix, ConnectionSpec spec, List<String> errors) {
        if (!positive(spec.getTimeout().getConnect())) {
            errors.add(prefix + "timeout.connect phải > 0");
        }
        if (!positive(spec.getTimeout().getValidation())) {
            errors.add(prefix + "timeout.validation phải > 0");
        }
        var retry = spec.getRetry();
        if (retry.getMaxAttempts() < 1) {
            errors.add(prefix + "retry.max-attempts phải >= 1");
        }
        if (retry.getBackoff() == null || retry.getBackoff().isNegative()) {
            errors.add(prefix + "retry.backoff không được âm");
        }
        if (retry.getMultiplier() < 1.0) {
            errors.add(prefix + "retry.multiplier phải >= 1");
        }
    }

    /** Placeholder chưa được resolve (ví dụ ${DB_URL}) nghĩa là thiếu biến môi trường. */
    private void checkPlaceholders(String prefix, ConnectionSpec spec, List<String> errors) {
        for (String value : new String[] {spec.getUrl(), spec.getUsername(), spec.getPassword(), spec.getUri(),
                spec.getHost(), spec.getDatabase()}) {
            if (value != null && value.contains("${")) {
                errors.add(prefix + "còn placeholder chưa được resolve (thiếu biến môi trường?)");
                return;
            }
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
