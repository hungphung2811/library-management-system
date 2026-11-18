package lms.imports.core;

/** Mã lỗi API nhập (ADR-0024 mục 3.3). */
public enum ImportErrorCode {
    FILE_REQUIRED(400, "Thiếu file hoặc file rỗng"),
    FILE_TOO_LARGE(413, "File vượt quá kích thước cho phép"),
    UNSUPPORTED_FILE(415, "Chỉ nhận file .xlsx thường (không macro, không mã hóa)"),
    FILE_UNSAFE(422, "File không an toàn để xử lý"),
    FILE_CORRUPT(422, "Không đọc được cấu trúc file Excel"),
    TEMPLATE_MISMATCH(422, "File không đúng mẫu"),
    EMPTY(422, "File không có dòng dữ liệu nào"),
    TOO_MANY_ROWS(422, "File vượt quá số dòng cho phép"),
    NOT_FOUND(404, "Không tìm thấy phiên nhập (không tồn tại, đã hết hạn hoặc đã hủy)"),
    INVALID_STATE(409, "Trạng thái phiên không cho phép thao tác này"),
    NOTHING_TO_COMMIT(409, "Không có dòng hợp lệ để ghi"),
    CREDENTIALS_GONE(410, "File mật khẩu tạm đã được tải hoặc đã hết hạn"),
    SESSION_LIMIT(429, "Đã có quá nhiều phiên nhập đang mở, hãy xác nhận hoặc hủy bớt"),
    BUSY(429, "Hệ thống đang bận xử lý nhập, thử lại sau"),
    STORAGE_UNAVAILABLE(503, "Kho lưu phiên nhập tạm thời không khả dụng");

    private final int httpStatus;
    private final String defaultMessage;

    ImportErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Mã trả về cho client, ví dụ {@code IMPORT_FILE_TOO_LARGE}. */
    public String code() {
        return "IMPORT_" + name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
