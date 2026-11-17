package lms.identity.user.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Nhập người dùng hàng loạt (tối đa {@value #MAX_ROWS} dòng). dryRun=true chỉ kiểm tra, không ghi gì. toString không in
 * mật khẩu tạm.
 */
public record ImportUsersRequest(boolean dryRun,
                                 @NotEmpty @Size(max = MAX_ROWS) List<@Valid Row> rows) {

    public static final int MAX_ROWS = 500;

    /** Không ràng buộc từng trường ở đây: kiểm theo dòng để báo lỗi theo dòng, không làm hỏng cả lô. */
    public record Row(String username, String fullName, String email, String departmentCode,
                      String temporaryPassword, Boolean reader) {
        @Override
        public String toString() {
            return "Row[username=" + username + ", temporaryPassword=***]";
        }
    }

    @Override
    public String toString() {
        return "ImportUsersRequest[dryRun=" + dryRun + ", rows=" + (rows == null ? 0 : rows.size()) + "]";
    }
}
