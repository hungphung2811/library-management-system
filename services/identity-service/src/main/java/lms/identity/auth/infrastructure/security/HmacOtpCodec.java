package lms.identity.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lms.identity.auth.application.OtpCodecPort;

/**
 * OTP 6 chữ số (SecureRandom) và băm HMAC-SHA256 theo (muối riêng từng dòng, userId, OTP). Khóa HMAC là bí mật cấu hình
 * (biến môi trường EMAIL_VERIFICATION_OTP_SECRET); để trống thì khóa là chính muối (vẫn băm có muối, chỉ mất lớp bí mật).
 * OTP chỉ có 10^6 khả năng nên chống rò DB dựa vào bí mật cấu hình + trần lần thử + hạn ngắn, không dựa vào băm một mình.
 */
public class HmacOtpCodec implements OtpCodecPort {

    private static final String ALG = "HmacSHA256";

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public HmacOtpCodec(String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String generateOtp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    @Override
    public String newSalt() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @Override
    public String hash(String salt, UUID userId, String otp) {
        try {
            Mac mac = Mac.getInstance(ALG);
            byte[] key = secret.length > 0 ? secret : salt.getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(key, ALG));
            byte[] out = mac.doFinal((salt + ":" + userId + ":" + otp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 không khả dụng", e);
        }
    }

    @Override
    public boolean matches(String salt, UUID userId, String otp, String expectedHash) {
        return MessageDigest.isEqual(hash(salt, userId, otp).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
