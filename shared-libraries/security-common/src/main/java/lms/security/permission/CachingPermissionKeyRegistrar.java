package lms.security.permission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Đăng ký khóa tối đa một lần cho mỗi khóa mỗi tiến trình: nhớ khóa đã đăng ký thành công, chạy bất đồng bộ
 * để không chặn request, và khi lỗi thì chờ một khoảng (cooldown) rồi mới thử lại ở lần gọi sau.
 */
public abstract class CachingPermissionKeyRegistrar implements PermissionKeyRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CachingPermissionKeyRegistrar.class);

    private final Set<String> registered = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Instant> lastFailure = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Executor executor;
    private final Clock clock;
    private final Duration cooldown;

    protected CachingPermissionKeyRegistrar(Executor executor, Clock clock, Duration cooldown) {
        this.executor = executor;
        this.clock = clock;
        this.cooldown = cooldown;
    }

    /** Executor một luồng nền, hàng đợi có giới hạn; đầy thì bỏ qua và thử lại ở lần gọi sau. */
    public static Executor backgroundExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "permission-key-registrar");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public final void ensureRegistered(EndpointDescriptor descriptor) {
        String key = descriptor.key();
        if (registered.contains(key)) return;
        Instant failedAt = lastFailure.get(key);
        if (failedAt != null && clock.instant().isBefore(failedAt.plus(cooldown))) return;
        if (!inFlight.add(key)) return;
        try {
            executor.execute(() -> register(descriptor));
        } catch (RejectedExecutionException e) {
            inFlight.remove(key);
        }
    }

    public boolean isRegistered(String key) {
        return registered.contains(key);
    }

    private void register(EndpointDescriptor descriptor) {
        try {
            doRegister(descriptor);
            registered.add(descriptor.key());
            lastFailure.remove(descriptor.key());
        } catch (RuntimeException e) {
            lastFailure.put(descriptor.key(), clock.instant());
            log.warn("Chưa đăng ký được khóa quyền {} (sẽ thử lại sau {}): {}", descriptor.key(), cooldown, e.toString());
        } finally {
            inFlight.remove(descriptor.key());
        }
    }

    /** Lưu khóa vào danh mục permission; phải idempotent. Ném RuntimeException nếu thất bại. */
    protected abstract void doRegister(EndpointDescriptor descriptor);
}
