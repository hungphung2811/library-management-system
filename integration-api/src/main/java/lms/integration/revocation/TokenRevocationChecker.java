package lms.integration.revocation;

import org.springframework.security.oauth2.jwt.Jwt;

/** Thu hồi tức thời token integration (tùy chọn, mặc định tắt). Fail-open: không xác định được thì coi là chưa thu hồi. */
public interface TokenRevocationChecker {

    /** @return true nếu token đã bị thu hồi (theo jti hoặc theo dấu thu hồi của application) */
    boolean isRevoked(Jwt jwt);
}
