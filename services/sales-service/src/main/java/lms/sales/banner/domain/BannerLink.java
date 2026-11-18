package lms.sales.banner.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Đường dẫn của banner: chỉ đường dẫn nội bộ tương đối với gốc storefront của tenant (ADR-0019 2.5). Hợp lệ khi:
 * tối đa 200 ký tự; bắt đầu bằng đúng một '/' (không "//"); chỉ gồm {@code [A-Za-z0-9-._~/?#=&+,%]} (nên mọi scheme,
 * khoảng trắng, '\', ký tự điều khiển, Unicode đều bị loại); mỗi '%' theo sau đúng hai chữ số hex; không chứa "//" và
 * không có đoạn ".." (kể cả dạng mã hóa %2e). Rỗng hoặc chỉ khoảng trắng thì là null (banner không bấm được). Không cắt
 * khoảng trắng của giá trị khác rỗng: có khoảng trắng là sai.
 */
public final class BannerLink {

    public static final int MAX_LENGTH = 200;

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._~/?#=&+,%-]+");
    private static final Pattern PERCENT_OK = Pattern.compile("(?:[^%]|%[0-9A-Fa-f]{2})*");
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("[/?#]");

    private BannerLink() {
    }

    /** Trả về chuỗi hợp lệ hoặc null; sai thì ném INVALID_BANNER (details field=linkPath). */
    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_LENGTH
                || raw.charAt(0) != '/'
                || raw.startsWith("//")
                || raw.contains("//")
                || !ALLOWED.matcher(raw).matches()
                || !PERCENT_OK.matcher(raw).matches()
                || hasDotDotSegment(raw)) {
            throw BannerText.invalid("linkPath");
        }
        return raw;
    }

    private static boolean hasDotDotSegment(String path) {
        for (String segment : SEGMENT_SPLIT.split(path)) {
            if (segment.toLowerCase(Locale.ROOT).replace("%2e", ".").equals("..")) {
                return true;
            }
        }
        return false;
    }
}
