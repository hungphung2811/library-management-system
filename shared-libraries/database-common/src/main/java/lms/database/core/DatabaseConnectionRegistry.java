package lms.database.core;

import lms.database.api.ConnectionSpec;
import lms.database.api.ConnectionState;
import lms.database.api.DatabaseConnection;
import lms.database.api.exception.ConnectionDisabledException;
import lms.database.api.exception.ConnectionInitializationException;
import lms.database.api.exception.ConnectionNotFoundException;
import lms.database.api.exception.ConnectionTypeMismatchException;
import lms.database.api.exception.DuplicateConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/** Nơi duy nhất giữ các connection. Thread-safe; tên được chuẩn hóa thành chữ hoa. */
public class DatabaseConnectionRegistry {

    private final ConcurrentMap<String, RegisteredConnection> entries = new ConcurrentHashMap<>();
    /** Thứ tự đăng ký, dùng để đóng ngược khi shutdown. */
    private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

    public void register(String name, ConnectionSpec spec, DatabaseConnection connection) {
        put(new RegisteredConnection(key(name), spec, ConnectionState.UP, connection, null));
    }

    public void registerDisabled(String name, ConnectionSpec spec) {
        put(new RegisteredConnection(key(name), spec, ConnectionState.DISABLED, null, "Connection bị tắt bằng cấu hình"));
    }

    public void registerFailed(String name, ConnectionSpec spec, Throwable cause) {
        put(new RegisteredConnection(key(name), spec, ConnectionState.FAILED, null, String.valueOf(cause.getMessage())));
    }

    private void put(RegisteredConnection entry) {
        if (entries.putIfAbsent(entry.name(), entry) != null) {
            throw new DuplicateConnectionException(entry.name());
        }
        order.add(entry.name());
    }

    public Optional<RegisteredConnection> entry(String name) {
        return Optional.ofNullable(entries.get(key(name)));
    }

    /** Trả connection đang mở; DISABLED/FAILED/không tồn tại cho ra Optional rỗng. */
    public Optional<DatabaseConnection> find(String name) {
        return entry(name).map(RegisteredConnection::connection);
    }

    /** Lấy connection đúng kiểu, ném exception rõ ràng nếu không có hoặc không dùng được. */
    public <T extends DatabaseConnection> T require(String name, Class<T> type) {
        RegisteredConnection entry = entries.get(key(name));
        if (entry == null) {
            throw new ConnectionNotFoundException(name, names());
        }
        switch (entry.state()) {
            case DISABLED -> throw new ConnectionDisabledException(entry.name());
            case FAILED -> throw new ConnectionInitializationException(entry.name(), new IllegalStateException(entry.detail()));
            default -> { /* UP */ }
        }
        DatabaseConnection connection = entry.connection();
        if (!type.isInstance(connection)) {
            throw new ConnectionTypeMismatchException(entry.name(), type, connection.getClass());
        }
        return type.cast(connection);
    }

    public ConnectionState state(String name) {
        return entry(name).map(RegisteredConnection::state)
                .orElseThrow(() -> new ConnectionNotFoundException(name, names()));
    }

    /** Tất cả mục (kể cả DISABLED/FAILED) theo thứ tự đăng ký. */
    public List<RegisteredConnection> entries() {
        List<RegisteredConnection> result = new ArrayList<>();
        for (String name : order) {
            RegisteredConnection entry = entries.get(name);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    public List<String> names() {
        return List.copyOf(order);
    }

    /** Gỡ khỏi registry; trả lại mục đã gỡ để người gọi đóng connection. */
    public Optional<RegisteredConnection> remove(String name) {
        RegisteredConnection removed = entries.remove(key(name));
        if (removed != null) {
            order.remove(removed.name());
        }
        return Optional.ofNullable(removed);
    }

    static String key(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên connection không được trống");
        }
        return name.trim().toUpperCase(Locale.ROOT);
    }
}
