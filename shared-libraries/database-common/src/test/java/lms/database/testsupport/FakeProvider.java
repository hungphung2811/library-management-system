package lms.database.testsupport;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseConnection;
import lms.database.api.DatabaseProvider;
import lms.database.api.DatabaseType;
import lms.database.api.HealthStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Provider giả cho unit test: không cần DB thật. */
public class FakeProvider implements DatabaseProvider<FakeProvider.FakeConnection> {

    private final DatabaseType type;
    private final List<String> closeLog;
    private final AtomicInteger createCalls = new AtomicInteger();
    private volatile Supplier<RuntimeException> failure = () -> null;
    private volatile int failTimes = 0;

    public FakeProvider(DatabaseType type, List<String> closeLog) {
        this.type = type;
        this.closeLog = closeLog;
    }

    /** Lần tạo đầu tiên (times lần) sẽ ném exception. */
    public FakeProvider failing(int times, Supplier<RuntimeException> failure) {
        this.failTimes = times;
        this.failure = failure;
        return this;
    }

    public int createCalls() {
        return createCalls.get();
    }

    @Override
    public DatabaseType type() {
        return type;
    }

    @Override
    public FakeConnection create(String name, ConnectionSpec spec) {
        int call = createCalls.incrementAndGet();
        if (call <= failTimes) {
            throw failure.get();
        }
        return new FakeConnection(name, type, closeLog);
    }

    public static class FakeConnection implements DatabaseConnection {

        private final String name;
        private final DatabaseType type;
        private final List<String> closeLog;
        private volatile HealthStatus health = HealthStatus.up("fake");
        private volatile boolean failOnClose;

        public FakeConnection(String name, DatabaseType type, List<String> closeLog) {
            this.name = name;
            this.type = type;
            this.closeLog = closeLog;
        }

        public FakeConnection withHealth(HealthStatus health) {
            this.health = health;
            return this;
        }

        public FakeConnection failOnClose() {
            this.failOnClose = true;
            return this;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public DatabaseType type() {
            return type;
        }

        @Override
        public HealthStatus health() {
            return health;
        }

        @Override
        public void close() {
            closeLog.add(name);
            if (failOnClose) {
                throw new IllegalStateException("lỗi khi đóng " + name);
            }
        }
    }
}
