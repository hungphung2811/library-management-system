package lms.sales.shared.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lms.sales.shared.domain.SalesException;
import lms.security.CurrentUser;
import lms.tenant.TenantContext;
import org.springframework.security.oauth2.jwt.Jwt;

/** Tiện ích cho controller: tenant, người gọi, phân trang, clientRequestId. */
public final class RequestSupport {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_SHOP_PAGE_SIZE = 50;

    private RequestSupport() {
    }

    /** Tenant của token (TenantRequiredException nếu không có, handler trả 403 TENANT_REQUIRED). */
    public static UUID tenant() {
        return TenantContext.require();
    }

    /** Người gọi từ JWT; null khi không có JWT (profile dev tắt xác thực). */
    public static UUID user(Jwt jwt) {
        return jwt == null ? null : CurrentUser.from(jwt).userId();
    }

    /** Trang bắt đầu từ 0. */
    public static int page(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    /** Trang cho khách: tối đa {@link #MAX_SHOP_PAGE_SIZE} (chống cào dữ liệu). */
    public static int shopSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_SHOP_PAGE_SIZE);
    }

    public static int size(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * clientRequestId từ client là chuỗi (UUID hoặc chuỗi bất kỳ tối đa 100 ký tự). UUID hợp lệ giữ nguyên; chuỗi khác
     * đổi thành UUID xác định theo nội dung (cùng chuỗi cho cùng UUID nên gửi lại vẫn idempotent).
     */
    public static UUID clientRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        String s = raw.trim();
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(("sales-client-request:" + s).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * clientRequestId của đơn online: BẮT BUỘC là UUID hợp lệ (không băm chuỗi bất kỳ: chuỗi đoán được như "cart-1"
     * dễ va chạm giữa các khách). Sai định dạng: 400 INVALID_REQUEST.
     */
    public static UUID strictClientRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw SalesException.invalidRequest("clientRequestId phải là UUID");
        }
    }
}
