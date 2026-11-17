package lms.sales.shared.domain;

/** Che dữ liệu cá nhân ở danh sách/chi tiết đơn (thiết kế mục 5.7). */
public final class PersonalDataMasker {

    private PersonalDataMasker() {
    }

    /** Giữ ký tự đầu mỗi từ: "Nguyen Van An" thành "N***** V** A*". */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            int first = word.offsetByCodePoints(0, 1);
            sb.append(word, 0, first).append("*".repeat(word.codePointCount(first, word.length())));
        }
        return sb.toString();
    }

    /**
     * Địa chỉ chỉ hiện quận/huyện và tỉnh/thành (hai đoạn cuối tách bởi dấu phẩy) nếu có từ ba đoạn trở lên, phần
     * còn lại che; ít đoạn hơn thì che hết.
     */
    public static String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String[] parts = address.trim().split("\\s*,\\s*");
        if (parts.length < 3) {
            return "***";
        }
        return "***, " + parts[parts.length - 2] + ", " + parts[parts.length - 1];
    }

    /** Giữ 3 số cuối. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String p = phone.trim();
        if (p.length() <= 3) {
            return "*".repeat(p.length());
        }
        return "*".repeat(p.length() - 3) + p.substring(p.length() - 3);
    }

    /** Giữ ký tự đầu phần trước '@' và tên miền: "binh@example.vn" thành "b***@example.vn". */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String e = email.trim();
        int at = e.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        int first = e.offsetByCodePoints(0, 1);
        return e.substring(0, first) + "***" + e.substring(at);
    }
}
