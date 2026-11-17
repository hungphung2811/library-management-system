package lms.identity.auth.domain;

/**
 * Tóm tắt user-agent thành nhãn ngắn để người dùng nhận ra thiết bị (ví dụ "Chrome trên Windows"). Không lưu chuỗi
 * user-agent gốc. Nhận diện bằng chuỗi con, không thư viện ngoài; có thể nhận sai (trình duyệt nhân Chromium tự xưng
 * Chrome), chỉ là nhãn hiển thị.
 */
public final class DeviceSummary {

    public static final String UNKNOWN = "Không rõ";
    public static final int MAX_LENGTH = 100;
    private static final int MAX_INPUT = 512;

    private DeviceSummary() {
    }

    public static String of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return UNKNOWN;
        String ua = userAgent.length() > MAX_INPUT ? userAgent.substring(0, MAX_INPUT) : userAgent;
        ua = ua.replaceAll("[\u0000-\u001F\u007F]", " ");
        String browser = browser(ua);
        String os = os(ua);
        String result;
        if (browser != null && os != null) result = browser + " trên " + os;
        else if (browser != null) result = browser;
        else if (os != null) result = os;
        else result = UNKNOWN;
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
    }

    private static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return null;
    }

    private static String os(String ua) {
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) return "iOS";
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
        if (ua.contains("CrOS")) return "ChromeOS";
        if (ua.contains("Linux") || ua.contains("X11")) return "Linux";
        return null;
    }
}
