package lms.identity.auth.infrastructure.web;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import lms.common.kernel.ErrorResponse;
import lms.identity.auth.application.IssueStorefrontTokenUseCase;
import lms.security.permission.PublicEndpoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cấp token dịch vụ storefront cho customer-api (ADR-0011 2.1). Công khai ở lớp xác thực, bảo vệ bằng bí mật riêng
 * STOREFRONT_CLIENT_SECRET trong header (không dùng INTERNAL_API_KEY); chưa cấu hình bí mật thì từ chối mọi lời gọi.
 * Mọi tenant không khả dụng cùng trả 404 SHOP_NOT_AVAILABLE với thân cố định.
 */
@PublicEndpoint
@RestController
@RequestMapping("/internal/storefront")
public class StorefrontAccessController {

    public static final String SECRET_HEADER = "X-Storefront-Client-Secret";

    private final IssueStorefrontTokenUseCase issue;
    private final byte[] secret;

    public StorefrontAccessController(IssueStorefrontTokenUseCase issue,
                                      @Value("${lms.identity.storefront.client-secret:}") String secret) {
        this.issue = issue;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record AccessTokenRequest(String tenantCode) {
    }

    /** allowedOrigins: origin ĐANG BẬT của tenant, đã chuẩn hóa (ADR-0013); không nằm trong claim JWT. Rỗng nếu chưa khai báo. */
    public record AccessTokenResponse(UUID tenantId, String code, String name, String accessToken, long expiresIn,
                                      List<String> allowedOrigins, List<String> modules) {
    }

    @PostMapping("/access-tokens")
    public ResponseEntity<?> issueAccessToken(@RequestHeader(value = SECRET_HEADER, required = false) String provided,
                                              @Valid @RequestBody AccessTokenRequest body) {
        byte[] given = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (secret.length == 0 || !MessageDigest.isEqual(secret, given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of("UNAUTHORIZED", "Thiếu hoặc sai bí mật storefront"));
        }
        return issue.execute(body.tenantCode())
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(new AccessTokenResponse(a.tenant().id(),
                        a.tenant().code(), a.tenant().name(), a.accessToken(), a.expiresIn(),
                        a.tenant().allowedOrigins(), a.tenant().modules())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("SHOP_NOT_AVAILABLE", "Cửa hàng không khả dụng")));
    }
}
