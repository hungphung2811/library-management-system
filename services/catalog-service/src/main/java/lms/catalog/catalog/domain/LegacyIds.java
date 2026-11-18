package lms.catalog.catalog.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Id tất định của dữ liệu backfill (ADR-0026 mục 4.2), tính được bằng SQL thuần ở mọi DB:
 * {@code md5('lms-work:' || title_id)::uuid} và {@code md5('lms-series:' || tenant_id || ':' || name_key)::uuid}.
 * md5 hex 32 ký tự ép sang uuid = đúng 16 byte của md5 (không có bit phiên bản), nên KHÔNG dùng
 * {@link UUID#nameUUIDFromBytes}. Chỉ đúng cho dữ liệu backfill; tác phẩm tạo mới dùng {@link UUID#randomUUID()}.
 * Circulation (reservations.work_id), sales (stock_items.work_id) và demo-seed dùng cùng công thức.
 */
public final class LegacyIds {

    private LegacyIds() {
    }

    /** Id tác phẩm 1:1 của một ấn bản (đầu sách) có sẵn trước V10. */
    public static UUID workIdOfTitle(UUID titleId) {
        return md5Uuid("lms-work:" + titleId);
    }

    /** Id bộ sách backfill; {@code nameKey} là {@code Reference.keyOf(tên)}. */
    public static UUID seriesId(UUID tenantId, String nameKey) {
        return md5Uuid("lms-series:" + tenantId + ":" + nameKey);
    }

    private static UUID md5Uuid(String text) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            ByteBuffer b = ByteBuffer.wrap(d);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
