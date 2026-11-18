package lms.identity.auth.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lms.identity.auth.application.IssueClientTokenUseCase;
import lms.identity.auth.application.IssueClientTokenUseCase.IssuedClientToken;
import lms.identity.auth.domain.ClientTokenException;
import lms.identity.auth.domain.ClientTokenException.Error;
import lms.security.permission.PublicEndpoint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token endpoint OAuth2 client_credentials (RFC 6749 mục 4.4) cho backend của tenant, qua integration-api. Công khai ở lớp
 * xác thực (đúng một đường dẫn, như {@link StorefrontAccessController}); bảo vệ bằng client_id + client_secret.
 *
 * <ul>
 *   <li>Content-Type bắt buộc {@code application/x-www-form-urlencoded}; mọi tham số ở THÂN, query string có nội dung
 *       bị từ chối {@code invalid_request} (secret không được nằm trên URL).</li>
 *   <li>Xác thực client bằng HTTP Basic hoặc client_id/client_secret trong thân; dùng cả hai, hoặc tham số lặp, là
 *       {@code invalid_request}.</li>
 *   <li>Mọi phản hồi (thành công lẫn lỗi) có {@code Cache-Control: no-store} và {@code Pragma: no-cache}; lỗi theo mục
 *       5.2 với thân JSON {@code {error, error_description}}; {@code invalid_client} là 401 kèm WWW-Authenticate và dùng
 *       chung cho mọi lý do thất bại.</li>
 *   <li>Không log tham số, header Authorization hay secret.</li>
 * </ul>
 */
@PublicEndpoint
@RestController
@RequestMapping("/internal/oauth2")
public class OAuthTokenController {

    private final IssueClientTokenUseCase issue;

    public OAuthTokenController(IssueClientTokenUseCase issue) {
        this.issue = issue;
    }

    /** Thân phản hồi thành công theo RFC 6749 mục 5.1. */
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("expires_in") long expiresIn,
                                @JsonProperty("scope") String scope) {
        @Override
        public String toString() {
            return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", scope=" + scope + "]";
        }
    }

    /** Thân lỗi theo RFC 6749 mục 5.2. */
    public record OAuthError(String error, @JsonProperty("error_description") String errorDescription) {
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(HttpServletRequest request) {
        try {
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
                throw new ClientTokenException(Error.INVALID_REQUEST);
            }
            if (!isFormUrlEncoded(request.getContentType())) {
                throw new ClientTokenException(Error.INVALID_REQUEST);
            }
            String grantType = single(request, "grant_type");
            String scope = single(request, "scope");
            String bodyId = single(request, "client_id");
            String bodySecret = single(request, "client_secret");
            String[] basic = basicCredentials(request.getHeader(HttpHeaders.AUTHORIZATION));

            String clientId;
            String clientSecret;
            if (basic != null && (bodyId != null || bodySecret != null)) {
                throw new ClientTokenException(Error.INVALID_REQUEST);
            }
            if (basic != null) {
                clientId = basic[0];
                clientSecret = basic[1];
            } else {
                clientId = bodyId;
                clientSecret = bodySecret;
            }
            IssuedClientToken token = issue.execute(grantType, clientId, clientSecret, scope);
            return noStore(ResponseEntity.ok())
                    .body(new TokenResponse(token.accessToken(), "Bearer", token.expiresIn(), token.scope()));
        } catch (ClientTokenException e) {
            return error(e.error());
        }
    }

    private static ResponseEntity<OAuthError> error(Error error) {
        ResponseEntity.BodyBuilder builder = noStore(ResponseEntity.status(error.status()));
        if (error == Error.INVALID_CLIENT) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"lms-integration\", charset=\"UTF-8\"");
        }
        return builder.contentType(MediaType.APPLICATION_JSON)
                .body(new OAuthError(error.code(), error.description()));
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache");
    }

    private static boolean isFormUrlEncoded(String contentType) {
        if (contentType == null) return false;
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Giá trị duy nhất của tham số thân; lặp tham số là invalid_request; rỗng coi như vắng. */
    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length == 0) return null;
        if (values.length > 1) throw new ClientTokenException(Error.INVALID_REQUEST);
        return values[0].isEmpty() ? null : values[0];
    }

    /**
     * {@code Authorization: Basic base64(urlencode(id):urlencode(secret))} (RFC 6749 mục 2.3.1). Null nếu không phải
     * Basic. Basic hỏng thì invalid_client (cùng mã với mọi lỗi xác thực client).
     */
    private static String[] basicCredentials(String header) {
        if (header == null || header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) throw new ClientTokenException(Error.INVALID_CLIENT);
            return new String[]{URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException e) {
            throw new ClientTokenException(Error.INVALID_CLIENT);
        }
    }
}
