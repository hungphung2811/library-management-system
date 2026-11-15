package lms.api.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lms.api.identity.client.IdentityGatewayClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Định tuyến tới identity-service: /api/platform/** (khu vực người vận hành, identity kiểm claim platformAdmin)
 * và GET /api/me/tenants (danh sách tenant của người dùng, không cần tenantId trong token).
 * Đường dẫn /t/{code} chỉ là đường dẫn của FE, không có route ở đây.
 */
@RestController
public class IdentityGatewayController {

    static final String PLATFORM_PREFIX = "/api/platform";

    private final IdentityGatewayClient identity;

    public IdentityGatewayController(IdentityGatewayClient identity) {
        this.identity = identity;
    }

    @RequestMapping("/api/platform/**")
    public ResponseEntity<byte[]> platform(HttpServletRequest request) throws IOException {
        String rest = request.getRequestURI().substring(PLATFORM_PREFIX.length());
        return identity.forward(HttpMethod.valueOf(request.getMethod()), "/internal/platform" + rest,
                request.getQueryString(), request.getInputStream().readAllBytes(), request.getContentType());
    }

    @GetMapping("/api/me/tenants")
    public ResponseEntity<byte[]> myTenants(HttpServletRequest request) {
        return identity.forward(HttpMethod.GET, "/internal/me/tenants", request.getQueryString(), null, null);
    }
}
