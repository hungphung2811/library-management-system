package lms.circulation.borrowing.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cổng đọc/ghi phục vụ job nhắc hạn, theo tenant hiện tại (TenantContext). Truy vấn duyệt keyset theo id phiếu và
 * trả tối đa {@code limit} dòng; số lần nhắc của cả lô được nạp bằng một truy vấn IN, không truy vấn từng phiếu.
 */
public interface LoanReminderPort {

    /**
     * Phiếu còn cuốn chưa trả có hạn trả trong [from, to] và chưa có nhắc DUE_SOON cho đúng hạn trả đó, id &gt; afterId,
     * sắp theo id.
     */
    List<LoanReminderCandidate> findDueSoon(LocalDate from, LocalDate to, UUID afterId, int limit);

    /**
     * Phiếu còn cuốn chưa trả có hạn trả trước {@code dueBefore}, đã nhắc OVERDUE (cho đúng hạn trả đó) ít hơn
     * {@code maxReminders} lần và chưa có lần nhắc nào sau {@code lastSentOnOrBefore}, id &gt; afterId, sắp theo id.
     */
    List<LoanReminderCandidate> findOverdue(LocalDate dueBefore, int maxReminders, LocalDate lastSentOnOrBefore,
                                            UUID afterId, int limit);

    /** Ghi và flush lô nhắc: vi phạm khóa duy nhất (chạy song song) lộ ra ngay, trước khi phát sự kiện. */
    void saveAll(List<LoanReminderRecord> records);
}
