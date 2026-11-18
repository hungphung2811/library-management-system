package lms.support.notification.domain;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thay tham số dạng đôi ngoặc nhọn quanh tên bằng giá trị: văn bản thuần, không thực thi gì (không có template
 * engine, chống SSTI khi tenant sửa mẫu). Tham số thiếu giá trị thành chuỗi rỗng. Tiêu đề bị bỏ ký tự xuống dòng
 * (chống chèn header thư).
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\s*\\}\\}");
    /** Đôi ngoặc còn sót sau khi trừ các tham số hợp lệ: sai cú pháp. */
    private static final Pattern STRAY = Pattern.compile("\\{\\{|\\}\\}");

    private TemplateRenderer() {
    }

    public static Set<String> paramsOf(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    /** Kiểm mẫu trước khi lưu: tham số lạ thì TemplateParamNotAllowedException; sai cú pháp thì InvalidTemplateException. */
    public static void validate(NotificationType type, String subject, String body) {
        Set<String> unknown = new HashSet<>();
        for (String text : new String[]{subject, body}) {
            for (String p : paramsOf(text)) {
                if (!type.allows(p)) unknown.add(p);
            }
            String rest = PLACEHOLDER.matcher(text == null ? "" : text).replaceAll("");
            if (STRAY.matcher(rest).find()) throw new InvalidTemplateException("Cú pháp tham số không hợp lệ");
        }
        if (!unknown.isEmpty()) throw new TemplateParamNotAllowedException(unknown);
    }

    public static MessageTemplate render(MessageTemplate template, Map<String, String> values) {
        return new MessageTemplate(singleLine(substitute(template.subject(), values)),
                substitute(template.body(), values), template.source(), template.version());
    }

    private static String substitute(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String singleLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
