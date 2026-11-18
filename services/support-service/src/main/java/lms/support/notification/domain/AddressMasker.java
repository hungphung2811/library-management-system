package lms.support.notification.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Che địa chỉ email để lưu/log (ví dụ {@code n***@gmail.com}) và băm địa chỉ làm khóa khử trùng. */
public final class AddressMasker {

    private AddressMasker() {
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "***";
        String e = email.trim();
        int at = e.indexOf('@');
        if (at <= 0) return "***";
        return e.charAt(0) + "***" + e.substring(at);
    }

    /** sha256(lower(email)) dạng hex: khóa khử trùng cho người nhận trực tiếp, không lộ địa chỉ. */
    public static String emailKey(String email) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
