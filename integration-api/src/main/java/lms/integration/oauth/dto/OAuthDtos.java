package lms.integration.oauth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO của token endpoint (RFC 6749 mục 4.4, 5.1, 5.2). Phản hồi tới client được dựng lại từ các trường allowlist này,
 * không chuyển nguyên thân của identity. {@code toString} không bao giờ in secret hay access token.
 */
public final class OAuthDtos {

    private OAuthDtos() {
    }

    /**
     * Yêu cầu chuyển tiếp tới identity. {@code basicAuthorization} là nguyên header {@code Authorization: Basic ...} của
     * client (null nếu client gửi client_id/client_secret trong thân).
     */
    public record TokenRequest(String basicAuthorization, String grantType, String scope, String clientId,
                               String clientSecret) {
        @Override
        public String toString() {
            return "TokenRequest[grantType=" + grantType + ", scope=" + scope + ", credentials=***]";
        }
    }

    /** Thân phản hồi thành công (mục 5.1). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("expires_in") long expiresIn,
                                @JsonProperty("scope") String scope) {
        @Override
        public String toString() {
            return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", scope=" + scope + "]";
        }
    }

    /** Thân lỗi (mục 5.2). */
    public record OAuthError(String error, @JsonProperty("error_description") String errorDescription) {
    }

    /** Thân identity trả khi lỗi: chỉ đọc trường {@code error}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstreamError(String error) {
    }
}
