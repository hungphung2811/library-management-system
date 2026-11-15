package lms.api.identity.client;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Cổng chuyển tiếp thô sang identity-service cho các API nền tảng (/api/platform/**) và /api/me/tenants:
 * identity tự kiểm claim (platformAdmin, tenantId) trên token được chuyển nguyên, lms-api không diễn giải body.
 */
public interface IdentityGatewayClient {

    /**
     * @param internalPath đường dẫn nội bộ, ví dụ /internal/platform/tenants
     * @param rawQuery     query string thô (đã mã hóa) hoặc null
     */
    ResponseEntity<byte[]> forward(HttpMethod method, String internalPath, String rawQuery, byte[] body,
                                   String contentType);
}
