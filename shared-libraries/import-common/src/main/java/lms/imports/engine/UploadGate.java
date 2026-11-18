package lms.imports.engine;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;

/** Giới hạn số lần đọc file đồng thời mỗi instance (không chờ): vượt thì {@code 429 IMPORT_BUSY}. */
public final class UploadGate {

    private final Semaphore permits;

    public UploadGate(int concurrency) {
        this.permits = new Semaphore(Math.max(1, concurrency));
    }

    public <T> T run(Supplier<T> action) {
        if (!permits.tryAcquire()) {
            throw new ImportException(ImportErrorCode.BUSY, ImportErrorCode.BUSY.defaultMessage(),
                    List.of("retryAfterSeconds=5"));
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }
}
