package lms.identity.auth.domain;

/**
 * Refresh token vừa được xoay vòng (trong cửa sổ ân hạn, hoặc request song song đã xoay trước): không phát token, không
 * thu hồi gì. Client đọc lại refresh token mới nhất rồi thử lại. HTTP 401 {@code REFRESH_TOKEN_SUPERSEDED}.
 */
public class RefreshTokenSupersededException extends InvalidRefreshTokenException {
    public RefreshTokenSupersededException() {
        super("Refresh token vừa được thay thế, hãy dùng refresh token mới nhất");
    }
}
