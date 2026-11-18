package lms.integration.config;

import java.io.IOException;
import lms.integration.web.IntegrationApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Chuyển CHÍNH token integration của người gọi xuống service hạ nguồn (catalog, sales). Service tự kiểm chữ ký, scope,
 * tenant (từ claim tenantId) và module bằng PermissionInterceptor: integration-api không tự xác định tenant và không
 * bao giờ gửi X-Tenant-Id hay token dịch vụ. Không có token trong ngữ cảnh bảo mật thì không gọi (401), tránh gọi
 * hạ nguồn không xác thực. Không log token.
 */
public class BearerForwardingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new IntegrationApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Token không hợp lệ hoặc thiếu");
        }
        request.getHeaders().remove(HttpHeaders.AUTHORIZATION);
        request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
        return execution.execute(request, body);
    }
}
