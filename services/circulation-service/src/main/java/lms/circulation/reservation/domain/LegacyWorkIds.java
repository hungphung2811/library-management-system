package lms.circulation.reservation.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Id tác phẩm 1:1 của một ấn bản có sẵn trước ADR-0026: {@code md5('lms-work:' || title_id)::uuid}, đúng công thức SQL
 * backfill của catalog V10 và circulation V13 (16 byte md5, không có bit phiên bản nên không dùng nameUUIDFromBytes).
 * Dùng khi catalog chưa gắn tác phẩm cho ấn bản (workId null) hoặc dòng đặt trước cũ chưa có work_id.
 */
public final class LegacyWorkIds {

    private LegacyWorkIds() {
    }

    public static UUID ofTitle(UUID titleId) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(("lms-work:" + titleId).getBytes(StandardCharsets.UTF_8));
            ByteBuffer b = ByteBuffer.wrap(d);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
