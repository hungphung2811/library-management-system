package lms.identity.user.application;

import java.util.List;
import java.util.UUID;
import java.util.Map;

/**
 * Định dạng CSV xuất người dùng (RFC 4180: phân tách bằng dấu phẩy, CRLF, ô chứa dấu phẩy/nháy kép/xuống dòng được bọc
 * nháy kép). Chống CSV injection: ô bắt đầu bằng = + - @ (hoặc tab/CR) được thêm dấu nháy đơn phía trước để bảng tính
 * không coi là công thức. BOM UTF-8 do tầng web thêm khi ghi byte.
 */
public final class UsersCsv {

    public static final String HEADER = "username,fullName,email,department,status,roles";

    private UsersCsv() {
    }

    public static String render(List<UserResponse> users, Map<UUID, String> departmentCodes,
                                Map<UUID, List<String>> roleNames) {
        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        for (UserResponse u : users) {
            String department = u.departmentId() == null ? "" : departmentCodes.getOrDefault(u.departmentId(), "");
            String roles = String.join(";", roleNames.getOrDefault(u.id(), List.of()));
            sb.append(cell(u.username())).append(',')
                    .append(cell(u.fullName())).append(',')
                    .append(cell(u.email())).append(',')
                    .append(cell(department)).append(',')
                    .append(cell(u.status() == null ? "" : u.status().name())).append(',')
                    .append(cell(roles)).append("\r\n");
        }
        return sb.toString();
    }

    /** Một ô: chống công thức rồi bọc nháy kép nếu cần. */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value;
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
