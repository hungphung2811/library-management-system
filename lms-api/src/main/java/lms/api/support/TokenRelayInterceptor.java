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

/** Gắn access token của người đang gọi và correlation id vào mọi lời gọi sang service nội bộ. */
public class TokenRelayInterceptor implements ClientHttpRequestInterceptor {

    static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AbstractOAuth2TokenAuthenticationToken<?> token) {
            request.getHeaders().setBearerAuth(token.getToken().getTokenValue());
        }
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !request.getHeaders().containsKey(CORRELATION_HEADER)) {
            request.getHeaders().set(CORRELATION_HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
