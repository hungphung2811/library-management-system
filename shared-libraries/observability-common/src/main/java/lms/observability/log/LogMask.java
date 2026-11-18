package lms.observability.log;

import java.util.regex.Pattern;

/**
 * Quy tắc che và lọc ký tự cho log (ADR-0029 D3, tài liệu thiết kế mục 4.2). Chỉ là hàm thuần, không phụ thuộc framework.
 * Mọi giá trị đi vào một dòng log phải qua {@link #safe(String)} để không chèn được dòng log giả.
 */
public final class LogMask {

    static final int MAX_LENGTH = 128;
    static final int PARTNER_MESSAGE_MAX = 100;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{6,}");
    private static final Pattern HEX_IP = Pattern.compile("[0-9A-Fa-f:.]+");

    private LogMask() {
    }

    private static boolean allowed(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == ':' || c == '/' || c == '{' || c == '}' || c == '@' || c == '='
                || c == '*' || c == '-';
    }

    /**
     * Bỏ CR/LF và ký tự điều khiển, đổi mọi ký tự ngoài {@code [A-Za-z0-9._:/{}@=*-]} thành {@code _}, cắt 128 ký tự.
     * Null hoặc rỗng thành {@code -}.
     */
    public static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        for (int i = 0; i < value.length() && sb.length() < MAX_LENGTH; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            sb.append(allowed(c) ? c : '_');
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    /** Mã tham chiếu: từ 10 ký tự giữ 3 đầu và 4 cuối; 6 đến 9 giữ 2 cuối; ngắn hơn thì {@code ***}. */
    public static String reference(String ref) {
        if (ref == null || ref.isBlank()) {
            return "-";
        }
        String r = ref.trim();
        int n = r.length();
        String masked;
        if (n >= 10) {
            masked = r.substring(0, 3) + "*".repeat(n - 7) + r.substring(n - 4);
        } else if (n >= 6) {
            masked = "*".repeat(n - 2) + r.substring(n - 2);
        } else {
            masked = "***";
        }
        return safe(masked);
    }

    /** Số tài khoản: {@code ****} và 4 số cuối; ít hơn 8 chữ số thì chỉ {@code ****}. */
    public static String lastDigits(String account) {
        if (account == null || account.isBlank()) {
            return "-";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < account.length(); i++) {
            char c = account.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.length() < 8 ? "****" : "****" + digits.substring(digits.length() - 4);
    }

    /**
     * Mã giao dịch của cổng: giữ tiền tố chữ (đến dấu {@code -} đầu tiên, tối đa 8 ký tự) rồi {@code ...} và 6 ký tự
     * cuối; phần còn lại không quá 6 ký tự thì chỉ {@code ***} (không lộ trọn mã). Dùng {@code ...} thay cho dấu ba chấm
     * Unicode vì {@link #safe(String)} chỉ cho ký tự ASCII.
     */
    public static String providerTxnId(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        String t = id.trim();
        String prefix = "";
        int dash = t.indexOf('-');
        if (dash > 0 && dash < 8 && t.substring(0, dash).chars().allMatch(Character::isLetter)) {
            prefix = t.substring(0, dash + 1);
        }
        String rest = t.substring(prefix.length());
        String masked = rest.length() <= 6 ? prefix + "***" : prefix + "..." + rest.substring(rest.length() - 6);
        return safe(masked);
    }

    /**
     * Thông điệp của đối tác khi buộc phải dùng: bỏ ký tự điều khiển, email thành {@code [email]}, dãy từ 6 chữ số
     * thành {@code [num]}, cắt 100 ký tự. Kết quả KHÔNG dành cho log (chứa dấu ngoặc); dùng cho thông điệp lỗi trả FE.
     */
    public static String partnerMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        String s = EMAIL.matcher(sb).replaceAll("[email]");
        s = LONG_NUMBER.matcher(s).replaceAll("[num]").trim();
        return s.length() > PARTNER_MESSAGE_MAX ? s.substring(0, PARTNER_MESSAGE_MAX) : s;
    }

    /** IP người gọi: IPv4 bỏ octet cuối ({@code 203.0.113.x}); IPv6 giữ 3 nhóm đầu (/48). Không hợp lệ thì {@code -}. */
    public static String clientIp(String ip) {
        if (ip == null || ip.isBlank() || !HEX_IP.matcher(ip.trim()).matches()) {
            return "-";
        }
        String v = ip.trim();
        if (v.indexOf(':') < 0) {
            String[] p = v.split("\\.", -1);
            return p.length == 4 ? p[0] + "." + p[1] + "." + p[2] + ".x" : "-";
        }
        String[] groups = v.split(":", -1);
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String g : groups) {
            if (g.isEmpty() || kept == 3) {
                break;
            }
            sb.append(sb.isEmpty() ? "" : ":").append(g);
            kept++;
        }
        return sb.isEmpty() ? "-" : sb + "::/48";
    }
}
