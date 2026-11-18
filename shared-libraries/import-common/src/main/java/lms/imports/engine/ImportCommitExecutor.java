package lms.imports.engine;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;

/** Pool nền chạy confirm: 1 luồng, hàng đợi giới hạn; đầy thì {@code 429 IMPORT_BUSY}. Tắt thì chờ việc hiện tại xong. */
public final class ImportCommitExecutor implements AutoCloseable {

    private final ThreadPoolExecutor pool;

    public ImportCommitExecutor(int queueCapacity) {
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "import-commit-" + n.incrementAndGet());
                    t.setDaemon(false);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void submit(Runnable task) {
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            throw new ImportException(ImportErrorCode.BUSY, ImportErrorCode.BUSY.defaultMessage(),
                    List.of("retryAfterSeconds=10"), e);
        }
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
