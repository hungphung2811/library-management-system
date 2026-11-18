package lms.sales.invoicing.domain;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Quy ước số hóa đơn: tuần tự theo (tenant, mẫu số, ký hiệu, năm), bắt đầu từ 1 mỗi năm theo cách hiểu Thông tư 78
 * (cần kế toán xác nhận). Năm tính theo giờ Việt Nam. Tên bộ đếm {@code INVOICE:<mẫu số>:<ký hiệu>:<năm>} phải vừa cột
 * {@code sales_counters.name} (30 ký tự).
 */
public final class InvoiceNumbers {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int MAX_COUNTER_NAME = 30;
    public static final int NUMBER_WIDTH = 8;

    private InvoiceNumbers() {
    }

    public static int fiscalYear(Instant at) {
        return at.atZone(ZONE).getYear();
    }

    public static String counterName(String templateCode, String series, int year) {
        String name = "INVOICE:" + templateCode + ":" + series + ":" + year;
        if (name.length() > MAX_COUNTER_NAME) {
            throw new IllegalArgumentException("Tên bộ đếm hóa đơn vượt " + MAX_COUNTER_NAME + " ký tự");
        }
        return name;
    }

    /** Số hiển thị đệm 8 chữ số, ví dụ 12 thành "00000012". */
    public static String display(long invoiceNo) {
        return String.format("%0" + NUMBER_WIDTH + "d", invoiceNo);
    }
}
