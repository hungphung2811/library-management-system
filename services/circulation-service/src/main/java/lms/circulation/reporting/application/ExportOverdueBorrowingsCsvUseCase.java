package lms.circulation.reporting.application;

import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.circulation.reporting.domain.OverdueCopy;
import lms.common.csv.CsvWriter;

/**
 * Báo cáo CSV mượn quá hạn (ADR-0018 mục 2.3), chạy đồng bộ trên luồng request (TenantContext là ThreadLocal nên không
 * được ghi ở luồng khác). Hai bước: {@link #prepare()} đếm trước để controller đặt header (kể cả cắt bớt) trước khi ghi
 * byte đầu; {@link Export#writeTo} ghi theo lô {@value #BATCH} dòng, tra tên độc giả và đầu sách theo lô. Tra tên lỗi thì
 * ô để trống, vẫn xuất đủ dòng. Không xuất email/điện thoại.
 * <p>Cột: borrowingId, userId, readerName, titleName, barcode, borrowDate, dueDate, daysOverdue (một dòng mỗi bản sao
 * chưa trả của phiếu quá hạn, quá hạn lâu nhất trước).
 */
public class ExportOverdueBorrowingsCsvUseCase {

    public static final int BATCH = 100;
    public static final String REPORT_NAME = "overdue-borrowings";

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LendingStatsPort stats;
    private final ReaderNameLookupPort readers;
    private final CopyLabelLookupPort copies;
    private final Clock clock;
    private final int maxRows;

    public ExportOverdueBorrowingsCsvUseCase(LendingStatsPort stats, ReaderNameLookupPort readers,
                                             CopyLabelLookupPort copies, Clock clock, int maxRows) {
        if (maxRows < 1) throw new IllegalArgumentException("maxRows phải >= 1");
        this.stats = stats;
        this.readers = readers;
        this.copies = copies;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    /** Đếm trước và chốt hôm nay; chưa ghi byte nào. */
    public Export prepare() {
        LocalDate today = LocalDate.now(clock);
        long total = stats.countOverdueCopies(today);
        int rows = (int) Math.min(total, maxRows);
        return new Export(today, rows, total > maxRows);
    }

    public final class Export {

        private final LocalDate today;
        private final int rows;
        private final boolean truncated;

        private Export(LocalDate today, int rows, boolean truncated) {
            this.today = today;
            this.rows = rows;
            this.truncated = truncated;
        }

        /** Số dòng dữ liệu dự kiến ghi (đã áp trần). */
        public int plannedRows() {
            return rows;
        }

        /** Vượt trần dòng: chỉ ghi {@code max-rows} dòng đầu. */
        public boolean truncated() {
            return truncated;
        }

        public String fileName() {
            return REPORT_NAME + "-" + FILE_DATE.format(today) + ".csv";
        }

        /**
         * Ghi BOM, hàng tiêu đề và các dòng vào {@code out} rồi flush (không đóng luồng). Trả số dòng dữ liệu đã ghi,
         * có thể khác {@link #plannedRows()} nếu dữ liệu đổi giữa lúc đếm và lúc đọc.
         */
        public int writeTo(OutputStream out) {
            CsvWriter csv = CsvWriter.toStream(out);
            csv.header("borrowingId", "userId", "readerName", "titleName", "barcode", "borrowDate", "dueDate", "daysOverdue");
            int written = 0;
            while (written < rows) {
                List<OverdueCopy> page = stats.overdueCopies(today, written, Math.min(BATCH, rows - written));
                if (page.isEmpty()) break;
                Set<UUID> userIds = new HashSet<>();
                Set<UUID> copyIds = new HashSet<>();
                for (OverdueCopy c : page) {
                    userIds.add(c.userId());
                    copyIds.add(c.bookCopyId());
                }
                Map<UUID, String> names = readers.names(userIds);
                Map<UUID, CopyLabelLookupPort.CopyLabel> labels = copies.labels(copyIds);
                for (OverdueCopy c : page) {
                    CopyLabelLookupPort.CopyLabel label = labels.get(c.bookCopyId());
                    csv.text(c.borrowingId().toString())
                            .text(c.userId().toString())
                            .text(names.get(c.userId()))
                            .text(label == null ? null : label.titleName())
                            .text(label == null ? null : label.barcode())
                            .date(c.borrowDate())
                            .date(c.dueDate())
                            .number(c.daysOverdue(today))
                            .endRow();
                }
                written += page.size();
                csv.flush();
            }
            csv.flush();
            return written;
        }
    }
}
