package lms.api.identity.client.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import lms.api.identity.client.IdentityGatewayClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Chuyển tiếp thô tới identity-service. RestClient truyền vào có TokenRelayInterceptor nên header Authorization
 * (Bearer của người gọi) đi nguyên vẹn. Status dưới 500 được chuyển nguyên; 5xx ném lỗi để thành 502.
 */
public class HttpIdentityGatewayClient implements IdentityGatewayClient {

    private final RestClient client;
    private final String baseUrl;

    public HttpIdentityGatewayClient(RestClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public ResponseEntity<byte[]> forward(HttpMethod method, String internalPath, String rawQuery, byte[] body,
                                          String contentType) {
        // URI dựng sẵn (không qua template) để query đã mã hóa không bị mã hóa lần hai
        URI uri = URI.create(baseUrl + internalPath + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery));
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (body != null && body.length > 0) {
            spec.contentType(contentType == null ? MediaType.APPLICATION_JSON : MediaType.parseMediaType(contentType));
            spec.body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] payload = response.getBody().readAllBytes();
            if (response.getStatusCode().is5xxServerError()) {
                throw HttpServerErrorException.create(response.getStatusCode(), response.getStatusText(),
                        response.getHeaders(), payload, StandardCharsets.UTF_8);
            }
            HttpHeaders out = new HttpHeaders();
            MediaType type = response.getHeaders().getContentType();
            if (type != null) out.setContentType(type);
            return ResponseEntity.status(response.getStatusCode()).headers(out).body(payload);
        });
    }
}
