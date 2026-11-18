package lms.imports.xlsx;

import java.text.Normalizer;
import java.util.Locale;

/** Chuẩn hóa chuỗi đọc từ ô và chống công thức Excel (formula injection) khi ghi lại. */
public final class CellSanitizer {

    private CellSanitizer() {
    }

    /** Bỏ ký tự điều khiển (trừ xuống dòng và tab), chuẩn hóa NFC, trim. */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean drop = (c < 0x20 && c != '\n' && c != '\t') || c == 0x7F || c == '﻿';
            if (drop && sb == null) {
                sb = new StringBuilder(raw.length());
                sb.append(raw, 0, i);
            } else if (!drop && sb != null) {
                sb.append(c);
            }
        }
        String cleaned = sb == null ? raw : sb.toString();
        return Normalizer.normalize(cleaned, Normalizer.Form.NFC).trim();
    }

    /** Tên cột/sheet để so khớp: NFC, bỏ dấu {@code *}, gộp khoảng trắng, chữ thường. */
    public static String normalizeHeader(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).replace("*", " ");
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Ô bắt đầu bằng ký tự mà Excel coi là bắt đầu công thức (hoặc chuỗi điều khiển): phải ghi với quotePrefixed. */
    public static boolean needsQuotePrefix(String value) {
        if (value == null || value.isEmpty()) return false;
        char c = value.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    /** Tên file chỉ để hiển thị: bỏ đường dẫn và ký tự điều khiển, cắt 100 ký tự. */
    public static String sanitizeFileName(String name) {
        if (name == null) return "";
        String s = name.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        String out = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).trim();
        return out.length() > 100 ? out.substring(0, 100) : out;
    }
}
