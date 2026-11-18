package lms.integration.config;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Chuyển correlation id sang service nội bộ. Cố ý KHÔNG chuyển Authorization của request gốc (token endpoint tự đặt). */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    static final String HEADER = "X-Correlation-Id";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !request.getHeaders().containsKey(HEADER)) {
            request.getHeaders().set(HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
