package lms.identity.tenantdata.application;

import java.util.concurrent.atomic.AtomicLong;

/** Ngân sách byte dữ liệu thô của một yêu cầu xuất (ADR-0031 D3: mặc định 2 GB); vượt thì SIZE_LIMIT. */
final class SizeBudget {

    private final long max;
    private final AtomicLong used = new AtomicLong();

    SizeBudget(long max) {
        this.max = max;
    }

    void add(long bytes) {
        if (used.addAndGet(bytes) > max) {
            throw new ExportFailure("SIZE_LIMIT");
        }
    }

    /** Trả lại phần đã tính của một lần thử nguồn bị hủy. */
    void release(long bytes) {
        used.addAndGet(-bytes);
    }

    long used() {
        return used.get();
    }
}
