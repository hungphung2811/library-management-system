package lms.identity.apiclient.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Client secret của Application INTEGRATION (D7): 32 byte CSPRNG, mã hóa base64url có tiền tố {@code lms_sk_}. Chỉ lưu
 * SHA-256 (hex) và 4 ký tự cuối; bản đầy đủ {@link #plain()} chỉ tồn tại trong phản hồi tạo credential. So sánh bằng
 * {@link MessageDigest#isEqual} (hằng thời gian). {@code toString} không bao giờ in secret.
 */
public record ClientSecret(String plain, String hash, String hint) {

    public static final String PREFIX = "lms_sk_";
    private static final int BYTES = 32;
    private static final int HINT_LENGTH = 4;

    public static ClientSecret generate(SecureRandom random) {
        byte[] raw = new byte[BYTES];
        random.nextBytes(raw);
        String plain = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new ClientSecret(plain, hash(plain), plain.substring(plain.length() - HINT_LENGTH));
    }

    /** SHA-256 hex (64 ký tự thường) của chuỗi secret đầy đủ. */
    public static String hash(String plain) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }

    /** So sánh hằng thời gian giữa secret trình bày và hash đã lưu. */
    public static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null) return false;
        return MessageDigest.isEqual(hash(presented).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String toString() {
        return "ClientSecret[hint=" + hint + "]";
    }
}
