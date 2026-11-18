package lms.observability.log;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Giảm trùng log theo khóa trong một cửa sổ thời gian: lần đầu của khóa được phát, các lần sau trong cửa sổ bị nuốt và
 * đếm; lần phát kế tiếp báo số dòng đã nuốt (trường {@code suppressed=N}). Giới hạn số khóa để bộ nhớ không tăng theo
 * dữ liệu đầu vào: vượt giới hạn thì gộp về khóa dự phòng. An toàn đa luồng (khóa thô, cửa sổ ngắn).
 */
public final class LogThrottle {

    /** {@code emit} = có nên ghi dòng này; {@code suppressed} = số dòng bị nuốt từ lần phát trước (chỉ có nghĩa khi emit). */
    public record Decision(boolean emit, int suppressed) {
    }

    private static final class Slot {
        long windowStartMillis;
        Duration window;
        int suppressed;
    }

    private final Clock clock;
    private final Duration defaultWindow;
    private final int maxKeys;
    private final Map<String, Slot> slots = new HashMap<>();

    public LogThrottle(Clock clock, Duration defaultWindow, int maxKeys) {
        this.clock = clock;
        this.defaultWindow = defaultWindow;
        this.maxKeys = Math.max(1, maxKeys);
    }

    public Decision acquire(String key) {
        return acquire(key, key, defaultWindow);
    }

    /**
     * @param key khóa chi tiết, ví dụ {@code anomalyType:intentId}
     * @param fallbackKey khóa dùng khi đã đủ {@code maxKeys} khóa mà {@code key} chưa có, ví dụ {@code anomalyType}
     * @param window cửa sổ giảm trùng của lần phát này
     */
    public synchronized Decision acquire(String key, String fallbackKey, Duration window) {
        long now = clock.millis();
        String k = key;
        Slot slot = slots.get(k);
        if (slot == null && slots.size() >= maxKeys) {
            evictExpired(now);
            if (slots.size() >= maxKeys) {
                k = fallbackKey;
                slot = slots.get(k);
            }
        }
        if (slot == null) {
            slot = new Slot();
            slot.windowStartMillis = now;
            slot.window = window;
            slots.put(k, slot);
            return new Decision(true, 0);
        }
        if (now - slot.windowStartMillis >= slot.window.toMillis()) {
            int suppressed = slot.suppressed;
            slot.windowStartMillis = now;
            slot.window = window;
            slot.suppressed = 0;
            return new Decision(true, suppressed);
        }
        slot.suppressed++;
        return new Decision(false, 0);
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Slot s = it.next().getValue();
            if (now - s.windowStartMillis >= s.window.toMillis()) {
                it.remove();
            }
        }
    }

    /** Số khóa đang giữ (cho test và theo dõi). */
    public synchronized int size() {
        return slots.size();
    }
}
