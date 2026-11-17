package lms.api.support;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Gắn access token của người đang gọi và correlation id vào mọi lời gọi sang service nội bộ. */
public class TokenRelayInterceptor implements ClientHttpRequestInterceptor {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AbstractOAuth2TokenAuthenticationToken<?> token) {
            request.getHeaders().setBearerAuth(token.getToken().getTokenValue());
        }
        // ADR-0016: chuyển nguyên X-Tenant-Id của người gọi; lms-api không diễn giải, service chỉ dùng khi token là platform admin
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String tenantHeader = attributes.getRequest().getHeader(TENANT_HEADER);
            if (tenantHeader != null && !tenantHeader.isBlank() && !request.getHeaders().containsKey(TENANT_HEADER)) {
                request.getHeaders().set(TENANT_HEADER, tenantHeader);
            }
        }
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !request.getHeaders().containsKey(CORRELATION_HEADER)) {
            request.getHeaders().set(CORRELATION_HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
