package lms.identity.auth.application;

import java.util.regex.Pattern;

/**
 * Thông tin hiển thị về client của lần phát token (IP và user-agent do BFF chuyển qua header). Chỉ để người dùng nhận
 * ra thiết bị, KHÔNG dùng cho quyết định bảo mật nào. IP sai định dạng thì bỏ (null).
 */
public record ClientInfo(String ip, String userAgent) {

    public static final ClientInfo NONE = new ClientInfo(null, null);

    private static final Pattern IPV4 = Pattern.compile("^([0-9]{1,3})[.]([0-9]{1,3})[.]([0-9]{1,3})[.]([0-9]{1,3})$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    public ClientInfo {
        ip = sanitizeIp(ip);
        userAgent = userAgent == null || userAgent.isBlank() ? null
                : (userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
    }

    public static ClientInfo of(String ip, String userAgent) {
        return new ClientInfo(ip, userAgent);
    }

    /** IPv4 hợp lệ hoặc IPv6 (chỉ ký tự hex, ':' và '.', có ít nhất hai dấu ':'), tối đa 45 ký tự; ngược lại null. */
    static String sanitizeIp(String raw) {
        if (raw == null) return null;
        String ip = raw.trim();
        if (ip.isEmpty() || ip.length() > 45) return null;
        var m = IPV4.matcher(ip);
        if (m.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(m.group(i)) > 255) return null;
            }
            return ip;
        }
        if (IPV6.matcher(ip).matches() && ip.chars().filter(c -> c == ':').count() >= 2) return ip;
        return null;
    }
}
