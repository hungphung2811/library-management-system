package lms.sales.shared.infrastructure.persistence;

/** Thoát ký tự đặc biệt của LIKE (dùng với escape ký tự gạch chéo ngược). */
public final class Like {

    private static final String BACKSLASH = "\\";

    private Like() {
    }

    public static String escape(String s) {
        return s.replace(BACKSLASH, BACKSLASH + BACKSLASH)
                .replace("%", BACKSLASH + "%")
                .replace("_", BACKSLASH + "_");
    }
}
