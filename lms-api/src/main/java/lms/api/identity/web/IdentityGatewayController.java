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
        return identity.forward(HttpMethod.valueOf(request.getMethod()), "/api/platform" + rest,
                request.getQueryString(), request.getInputStream().readAllBytes(), request.getContentType());
    }

    @GetMapping("/api/me/tenants")
    public ResponseEntity<byte[]> myTenants(HttpServletRequest request) {
        return identity.forward(HttpMethod.GET, "/internal/me/tenants", request.getQueryString(), null, null);
    }

    /**
     * Hợp đồng thuê dịch vụ của chính tenant (ADR-0012, chỉ đọc). Identity kiểm khóa động
     * {@code TenantContractController/list} và {@code .../current} trên token được chuyển tiếp; chỉ có GET.
     */
    @GetMapping("/api/contracts")
    public ResponseEntity<byte[]> myContracts(HttpServletRequest request) {
        return identity.forward(HttpMethod.GET, "/internal/contracts", request.getQueryString(), null, null);
    }

    /**
     * Origin storefront của chính tenant (ADR-0013, chỉ đọc). Identity kiểm khóa động
     * {@code TenantStorefrontOriginController/list} trên token được chuyển tiếp; chỉ có GET. Platform admin quản lý qua
     * {@code /api/platform/tenants/{tenantId}/storefront-origins} (chuyển nguyên).
     */
    @GetMapping("/api/storefront-origins")
    public ResponseEntity<byte[]> myStorefrontOrigins(HttpServletRequest request) {
        return identity.forward(HttpMethod.GET, "/internal/storefront-origins", request.getQueryString(), null, null);
    }

    /**
     * Application, scope, domain allowlist và credential của chính tenant (SaaS API Authentication P1, ADR-0027). Chuyển
     * nguyên sang {@code /internal/applications/**}; identity kiểm khóa động {@code ApplicationController/<method>} trên
     * token được chuyển tiếp và tenant lấy từ token. Phản hồi luôn gắn {@code Cache-Control: no-store} (phản hồi tạo
     * credential chứa secret một lần). Không log thân request/phản hồi. Platform admin dùng
     * {@code /api/platform/tenants/{tenantId}/applications} (chuyển nguyên, không sửa gì).
     */
    @RequestMapping({"/api/applications", "/api/applications/**"})
    public ResponseEntity<byte[]> applications(HttpServletRequest request) throws IOException {
        String rest = request.getRequestURI().substring("/api/applications".length());
        ResponseEntity<byte[]> response = identity.forward(HttpMethod.valueOf(request.getMethod()),
                "/internal/applications" + rest, request.getQueryString(), request.getInputStream().readAllBytes(),
                request.getContentType());
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(response.getBody());
    }

    @GetMapping("/api/contracts/current")
    public ResponseEntity<byte[]> myCurrentContract(HttpServletRequest request) {
        return identity.forward(HttpMethod.GET, "/internal/contracts/current", request.getQueryString(), null, null);
    }
}
