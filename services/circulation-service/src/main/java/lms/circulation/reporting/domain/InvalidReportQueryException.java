package lms.circulation.reporting.domain;

/** Tham số thống kê/báo cáo không hợp lệ (khoảng ngày, độ chia, limit); {@code code} ánh xạ sang mã lỗi 400. */
public class InvalidReportQueryException extends RuntimeException {

    private final String code;

    public InvalidReportQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
