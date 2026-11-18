package lms.integration.oauth.web;

import jakarta.servlet.http.HttpServletRequest;
import lms.integration.oauth.client.OAuthClient;
import lms.integration.oauth.client.OAuthClient.OAuthErrorCode;
import lms.integration.oauth.client.OAuthClient.TokenResult;
import lms.integration.oauth.dto.OAuthDtos.OAuthError;
import lms.integration.oauth.dto.OAuthDtos.TokenRequest;
import lms.integration.web.IntegrationApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token endpoint OAuth2 client_credentials cho backend của tenant (RFC 6749 mục 4.4): chuyển form tới identity và trả
 * nguyên ngữ nghĩa RFC (200 token, 400 invalid_request/unsupported_grant_type/invalid_scope, 401 invalid_client kèm
 * WWW-Authenticate). Mọi phản hồi có {@code Cache-Control: no-store} và {@code Pragma: no-cache}; lỗi hạ tầng là 503
 * thân {@code {"error":"temporarily_unavailable"}}. Không log tham số, Authorization hay secret.
 *
 * <p>Chấp nhận Basic hoặc client_id/client_secret trong thân; không chấp nhận tham số trên query string. Việc kiểm tra
 * thông tin client, scope, trạng thái application/tenant là của identity; BFF chỉ kiểm hình dạng yêu cầu.
 */
@RestController
@RequestMapping("/api/integration/v1/oauth")
public class OAuthTokenController {

    private final OAuthClient oauth;

    public OAuthTokenController(OAuthClient oauth) {
        this.oauth = oauth;
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(HttpServletRequest request) {
        try {
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
                return error(OAuthErrorCode.INVALID_REQUEST);
            }
            if (!isFormUrlEncoded(request.getContentType())) {
                return error(OAuthErrorCode.INVALID_REQUEST);
            }
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            String basic = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)
                    ? authorization : null;
            TokenRequest forward = new TokenRequest(basic, single(request, "grant_type"), single(request, "scope"),
                    single(request, "client_id"), single(request, "client_secret"));
            // Mọi quyết định xác thực (kể cả thiếu grant_type/thiếu thông tin client) là của identity, không đoán ở BFF
            return forwardToIdentity(forward);
        } catch (RepeatedParameterException e) {
            return error(OAuthErrorCode.INVALID_REQUEST);
        } catch (IntegrationApiException e) {
            return unavailable();
        }
    }

    private ResponseEntity<?> forwardToIdentity(TokenRequest forward) {
        TokenResult result = oauth.requestToken(forward);
        if (result.isSuccess()) {
            return noStore(ResponseEntity.ok()).contentType(MediaType.APPLICATION_JSON).body(result.token());
        }
        return error(result.error());
    }

    private static ResponseEntity<OAuthError> error(OAuthErrorCode code) {
        ResponseEntity.BodyBuilder builder = noStore(ResponseEntity.status(code.status()));
        if (code == OAuthErrorCode.INVALID_CLIENT) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"lms-integration\", charset=\"UTF-8\"");
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(new OAuthError(code.code(), code.description()));
    }

    private static ResponseEntity<OAuthError> unavailable() {
        return noStore(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)).contentType(MediaType.APPLICATION_JSON)
                .body(new OAuthError("temporarily_unavailable", "Dịch vụ tạm thời không khả dụng, vui lòng thử lại sau"));
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
        if (values.length > 1) throw new RepeatedParameterException();
        return values[0].isEmpty() ? null : values[0];
    }

    private static final class RepeatedParameterException extends RuntimeException {
        RepeatedParameterException() {
            super(null, null, false, false);
        }
    }
}
