package lms.sales.oplog.infrastructure.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lms.sales.oplog.application.OperationalLogStore;
import lms.sales.oplog.domain.OperationalLogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hàng đợi có giới hạn + một luồng nền ghi theo lô. {@link #offer} không bao giờ chặn: đầy thì bỏ và đếm. Lỗi ghi DB chỉ
 * đếm và log cảnh báo (tên lớp lỗi, không thông điệp), không ảnh hưởng luồng nghiệp vụ. Logger của lớp này nằm ngoài
 * {@code lms.log.*} nên không tự nạp lại chính nó.
 */
public class OperationalLogWriter {

    private static final Logger log = LoggerFactory.getLogger(OperationalLogWriter.class);
    private static final long REPORT_INTERVAL_MS = 60_000;

    private final OperationalLogStore store;
    private final BlockingQueue<OperationalLogEvent> queue;
    private final int batchSize;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running;
    private Thread thread;
    private long reportedDropped;
    private long lastReport;

    public OperationalLogWriter(OperationalLogStore store, int queueCapacity, int batchSize) {
        this.store = store;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
    }

    /** Không chặn: đầy thì bỏ dòng và đếm. */
    public boolean offer(OperationalLogEvent event) {
        if (event == null) {
            return false;
        }
        boolean ok = queue.offer(event);
        if (!ok) {
            dropped.incrementAndGet();
        }
        return ok;
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    public long failed() {
        return failed.get();
    }

    public int queued() {
        return queue.size();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "oplog-writer");
        thread.setDaemon(true);
        thread.start();
    }

    /** Dừng và ghi nốt phần còn trong hàng đợi (chờ tối đa vài giây). */
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private void loop() {
        List<OperationalLogEvent> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                OperationalLogEvent first = queue.poll(300, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                    flush(batch);
                    batch.clear();
                }
                reportDropped();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                batch.clear();
            }
        }
    }

    private void flush(List<OperationalLogEvent> batch) {
        try {
            store.saveBatch(List.copyOf(batch));
            written.addAndGet(batch.size());
        } catch (RuntimeException e) {
            failed.addAndGet(batch.size());
            log.warn("Không lưu được lô log vận hành ({} dòng): {}", batch.size(), e.getClass().getSimpleName());
        }
    }

    private void reportDropped() {
        long now = System.currentTimeMillis();
        long d = dropped.get();
        if (d > reportedDropped && now - lastReport >= REPORT_INTERVAL_MS) {
            log.warn("Hàng đợi log vận hành đầy: đã bỏ {} dòng (tổng {})", d - reportedDropped, d);
            reportedDropped = d;
            lastReport = now;
        }
    }
}
