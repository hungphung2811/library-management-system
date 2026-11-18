package lms.demo.plan;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /** Bộ sách demo (id gợi ý; xem {@link DemoPlan.SeriesPlan}). */
    public static UUID series(int n) {
        return of("series", n);
    }

    /** Tác phẩm demo không phải 1:1 với một ấn bản gốc (ví dụ bộ hộp). */
    public static UUID work(int n) {
        return of("work", n);
    }

    /**
     * Id tác phẩm 1:1 của một ấn bản có sẵn: {@code md5('lms-work:' || title_id)::uuid}, đúng công thức backfill của
     * catalog V10 / circulation V13 / sales V14 (md5 hex ép sang uuid, KHÔNG phải nameUUIDFromBytes). Có test so với
     * {@code LegacyIds} của catalog.
     */
    public static UUID legacyWork(UUID titleId) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(("lms-work:" + titleId).getBytes(StandardCharsets.UTF_8));
            ByteBuffer b = ByteBuffer.wrap(d);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
