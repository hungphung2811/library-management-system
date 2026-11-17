package lms.identity.contract.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Mốc nhắc sắp hết hạn (ADR-0012 mục 2.4.3). Mốc {@code d} đến hạn khi hôm nay (giờ VN) &gt;= endDate − d. Mỗi lượt gửi
 * mốc GẤP NHẤT đã đến hạn mà chưa có dòng; các mốc lớn hơn chưa có dòng được đánh dấu bỏ qua (job ngừng lâu thì không
 * dội nhiều thông báo cùng lúc).
 */
public final class ReminderSchedule {

    /** {@code send} null nếu không có gì phải gửi. */
    public record Decision(Integer send, List<Integer> skipped) {

        public boolean isEmpty() {
            return send == null;
        }
    }

    private ReminderSchedule() {
    }

    public static Decision decide(Collection<Integer> reminderDays, LocalDate today, LocalDate endDate,
                                  Set<Integer> alreadyRecorded) {
        List<Integer> due = reminderDays.stream().distinct().sorted()
                .filter(d -> !today.isBefore(endDate.minusDays(d))).toList();
        List<Integer> pending = due.stream().filter(d -> !alreadyRecorded.contains(d)).toList();
        if (pending.isEmpty()) return new Decision(null, List.of());
        // due đã sắp tăng dần: phần tử nhỏ nhất là mốc gấp nhất
        int urgent = pending.get(0);
        // đã có dòng của mốc gấp hơn rồi thì mốc lớn hơn không còn ý nghĩa nữa (đã nhắc gần hơn)
        boolean moreUrgentRecorded = due.stream().anyMatch(d -> d < urgent && alreadyRecorded.contains(d));
        if (moreUrgentRecorded) return new Decision(null, pending);
        return new Decision(urgent, pending.stream().filter(d -> d > urgent).toList());
    }
}
