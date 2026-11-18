package lms.integration.oauth.client;

import lms.integration.oauth.dto.OAuthDtos.TokenRequest;
import lms.integration.oauth.dto.OAuthDtos.TokenResponse;

/** Cổng tới token endpoint của identity (POST /internal/oauth2/token). */
public interface OAuthClient {

    /**
     * Kết quả có nghĩa: hoặc token, hoặc lỗi OAuth2 hợp lệ do identity trả (invalid_request, invalid_client,
     * unsupported_grant_type, invalid_scope). Mọi tình huống khác (5xx, mất kết nối, thân lạ) là
     * {@link lms.integration.web.IntegrationApiException#serviceUnavailable()}.
     */
    TokenResult requestToken(TokenRequest request);

    /** Hoặc {@code token} khác null, hoặc {@code error} khác null. */
    record TokenResult(TokenResponse token, OAuthErrorCode error) {

        public static TokenResult success(TokenResponse token) {
            return new TokenResult(token, null);
        }

        public static TokenResult failure(OAuthErrorCode error) {
            return new TokenResult(null, error);
        }

        public boolean isSuccess() {
            return token != null;
        }
    }

    /** Mã lỗi RFC 6749 mục 5.2 mà BFF chấp nhận chuyển tiếp (tập đóng). */
    enum OAuthErrorCode {
        INVALID_REQUEST("invalid_request", 400, "Yêu cầu không hợp lệ"),
        INVALID_CLIENT("invalid_client", 401, "Xác thực client thất bại"),
        UNSUPPORTED_GRANT_TYPE("unsupported_grant_type", 400, "Chỉ hỗ trợ grant_type=client_credentials"),
        INVALID_SCOPE("invalid_scope", 400, "Scope không hợp lệ");

        private final String code;
        private final int status;
        private final String description;

        OAuthErrorCode(String code, int status, String description) {
            this.code = code;
            this.status = status;
            this.description = description;
        }

        public String code() { return code; }

        public int status() { return status; }

        /** Thông điệp do integration-api viết (không chuyển nguyên của identity). */
        public String description() { return description; }

        public static OAuthErrorCode fromCode(String code) {
            for (OAuthErrorCode value : values()) {
                if (value.code.equals(code)) return value;
            }
            return null;
        }
    }
}
