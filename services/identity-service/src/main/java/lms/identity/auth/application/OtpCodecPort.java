package lms.identity.auth.application;

import java.util.UUID;

/** Sinh OTP và băm OTP có muối (HMAC); không bao giờ lưu OTP thô. */
public interface OtpCodecPort {

    /** OTP 6 chữ số, sinh bằng nguồn ngẫu nhiên an toàn. */
    String generateOtp();

    String newSalt();

    String hash(String salt, UUID userId, String otp);

    /** So khớp thời gian hằng. */
    boolean matches(String salt, UUID userId, String otp, String expectedHash);
}
