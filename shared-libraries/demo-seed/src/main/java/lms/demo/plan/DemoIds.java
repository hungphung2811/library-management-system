package lms.demo.plan;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** UUID tất định cho dữ liệu demo: {@code UUID.nameUUIDFromBytes("lms-demo:<loại>:<n>")}. Cùng (loại, n) luôn ra cùng id ở mọi service. */
public final class DemoIds {

    private DemoIds() {
    }

    public static UUID of(String kind, int n) {
        return UUID.nameUUIDFromBytes(("lms-demo:" + kind + ":" + n).getBytes(StandardCharsets.UTF_8));
    }

    /** Id gợi ý cho tenant demo (identity có thể dùng hoặc tự sinh rồi in {@code DEMO_TENANT_ID}). */
    public static UUID tenant() {
        return of("tenant", 0);
    }

    public static UUID staff(int n) {
        return of("staff", n);
    }

    public static UUID reader(int n) {
        return of("reader", n);
    }

    public static UUID title(int n) {
        return of("title", n);
    }

    public static UUID copy(int n) {
        return of("copy", n);
    }

    public static UUID borrowing(int n) {
        return of("borrowing", n);
    }

    public static UUID reservation(int n) {
        return of("reservation", n);
    }

    public static UUID fine(int n) {
        return of("fine", n);
    }

    public static UUID supplier(int n) {
        return of("supplier", n);
    }

    public static UUID receipt(int n) {
        return of("receipt", n);
    }

    public static UUID order(int n) {
        return of("order", n);
    }

    public static UUID salesReturn(int n) {
        return of("sales-return", n);
    }

    public static UUID refund(int n) {
        return of("refund", n);
    }
}
