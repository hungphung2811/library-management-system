package lms.circulation.reporting.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lms.circulation.reporting.domain.DateRange;
import lms.circulation.reporting.domain.LendingCounts;
import lms.circulation.reporting.domain.OverdueCopy;
import lms.circulation.reporting.domain.TopTitle;
import lms.circulation.reporting.domain.UnpaidFines;

/**
 * Cổng đọc số liệu mượn trả/phạt của tenant hiện tại (read model, chỉ đọc). Mọi truy vấn lọc theo tenant của phiên;
 * ngày là ngày giờ VN (cột DATE của circulation).
 */
public interface LendingStatsPort {

    LendingCounts counts(LocalDate today);

    UnpaidFines unpaidFines();

    /** Số bản sao mượn theo borrow_date trong khoảng, chỉ ngày có dữ liệu. */
    Map<LocalDate, Long> borrowedPerDay(DateRange range);

    /** Số bản sao đã trả (GOOD/DAMAGED, không tính báo mất) theo return_date trong khoảng, chỉ ngày có dữ liệu. */
    Map<LocalDate, Long> returnedPerDay(DateRange range);

    /** Top đầu sách theo số bản sao mượn trong khoảng; bỏ dòng cũ không có title_id; count giảm dần, đồng hạng titleId tăng. */
    List<TopTitle> topBorrowedTitles(DateRange range, int limit);

    /** Số bản sao chưa trả của phiếu quá hạn (dueDate &lt; today). */
    long countOverdueCopies(LocalDate today);

    /** Trang các bản sao chưa trả của phiếu quá hạn, dueDate tăng dần (quá hạn lâu nhất trước), thứ tự ổn định. */
    List<OverdueCopy> overdueCopies(LocalDate today, int offset, int limit);
}
