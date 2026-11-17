package lms.customer.storefront;

import java.util.UUID;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Kiểm phiên khách cho các endpoint cần đăng nhập (C2 dùng cho /public/t/{code}/orders/**, ADR-0011 2.4):
 * token có claim svc (token dịch vụ lọt ra) thì 401; tenantId của token khác tenant đã phân giải từ {code}
 * (kể cả token không có tenantId) thì 403 SHOP_SESSION_MISMATCH. Chỉ gọi sau khi {code} đã được xác nhận khả dụng.
 */
public class ShopSessionGuard {

    static final String SERVICE_CLAIM = "svc";
    static final String TENANT_CLAIM = "tenantId";
    static final String AUDIENCE = "lms-storefront";

    /** Trả Jwt của khách khi hợp lệ, để caller chuyển tiếp token của khách sang sales. */
    public Jwt requireSession(StorefrontAccess shop, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw unauthorized();
        }
        Jwt jwt = jwtAuth.getToken();
        if (jwt.hasClaim(SERVICE_CLAIM)) {
            throw unauthorized();
        }
        // Phòng thủ nhiều lớp: bộ giải mã đã bắt buộc aud, nhưng token nhân viên không bao giờ được vào route khách.
        if (jwt.getAudience() == null || !jwt.getAudience().contains(AUDIENCE)) {
            throw unauthorized();
        }
        if (!shop.tenantId().equals(tenantOf(jwt))) {
            throw new PublicApiException(HttpStatus.FORBIDDEN, "SHOP_SESSION_MISMATCH",
                    "Phiên đăng nhập không thuộc cửa hàng này");
        }
        return jwt;
    }

    private static UUID tenantOf(Jwt jwt) {
        Object raw = jwt.getClaim(TENANT_CLAIM);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static PublicApiException unauthorized() {
        return new PublicApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Cần đăng nhập");
    }
}
