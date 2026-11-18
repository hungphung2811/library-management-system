package lms.integration.ratelimit;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Danh tính người gọi LẤY TỪ CLAIM của token đã xác thực (chữ ký, iss, aud, hạn): tenantId, application_id, jti. Không
 * bao giờ lấy từ header, query hay thân request. Claim thiếu hoặc không phải UUID thì {@link #from} trả null (khóa
 * Redis chỉ chứa UUID đã parse nên không thể bị chèn ký tự lạ).
 */
public record ApiCaller(UUID tenantId, UUID applicationId, String jti) {

    public static ApiCaller from(Jwt jwt) {
        UUID tenant = uuid(jwt.getClaimAsString("tenantId"));
        UUID application = uuid(jwt.getClaimAsString("application_id"));
        if (tenant == null || application == null) return null;
        return new ApiCaller(tenant, application, jwt.getId());
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
