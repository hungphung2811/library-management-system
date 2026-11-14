package lms.database.core;

import lms.database.api.RetryProperties;
import lms.database.api.exception.RetryExhaustedException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chạy một thao tác với retry và backoff lũy thừa. */
public class RetryExecutor {

    /** Tách việc ngủ ra để test không phải chờ thật. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final Sleeper sleeper;

    public RetryExecutor() {
        this(duration -> Thread.sleep(duration.toMillis()));
    }

    public RetryExecutor(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public <T> T execute(String description, RetryProperties retry, Supplier<T> action) {
        int attempts = Math.max(1, retry.getMaxAttempts());
        Duration delay = retry.getBackoff();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                if (attempt == attempts) {
                    break;
                }
                log.warn("{} thất bại (lần {}/{}): {}. Thử lại sau {} ms", description, attempt, attempts,
                        e.getMessage(), delay.toMillis());
                pause(delay);
                delay = Duration.ofMillis(Math.round(delay.toMillis() * retry.getMultiplier()));
            }
        }
        throw new RetryExhaustedException(description, attempts, last);
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bị ngắt trong lúc chờ retry", e);
        }
    }
}
