package lms.identity.auth.application;

public interface RefreshTokenCodecPort {
    /** Sinh refresh token ngẫu nhiên không đoán được. */
    String generate();

    /** Băm một chiều để lưu/tra cứu mà không giữ token gốc. */
    String hash(String rawToken);
}
