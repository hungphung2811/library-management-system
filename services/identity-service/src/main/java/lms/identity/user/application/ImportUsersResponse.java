package lms.identity.user.application;

import java.util.List;

/**
 * total: số dòng nhận được; created: số dòng đã tạo (0 khi dryRun); failed: số dòng lỗi. results theo thứ tự dòng đầu vào.
 * Chứa mật khẩu tạm do hệ thống sinh: chỉ trả đúng một lần ở đây, KHÔNG được log hay lưu lại.
 */
public record ImportUsersResponse(int total, int created, int failed, List<ImportRowResult> results) {

    /** row: số thứ tự dòng bắt đầu từ 1. status: CREATED, VALID (dryRun, hợp lệ) hoặc FAILED. */
    public record ImportRowResult(int row, String username, String status, String errorCode, String message,
                                  String temporaryPassword) {

        public static final String CREATED = "CREATED";
        public static final String VALID = "VALID";
        public static final String FAILED = "FAILED";

        @Override
        public String toString() {
            return "ImportRowResult[row=" + row + ", username=" + username + ", status=" + status
                    + ", errorCode=" + errorCode + "]";
        }
    }

    @Override
    public String toString() {
        return "ImportUsersResponse[total=" + total + ", created=" + created + ", failed=" + failed + "]";
    }
}
