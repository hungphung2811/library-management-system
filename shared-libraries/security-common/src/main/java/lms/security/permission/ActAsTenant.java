package lms.security.permission;

import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Tenant đích mà platform admin đang hành động thay (ADR-0016) của request hiện tại, để client liên service chuyển
 * tiếp header {@link #HEADER} khi gọi service khác. Giá trị chỉ có khi {@link PermissionInterceptor} đã xác nhận
 * người gọi là platform admin và header hợp lệ, nên không bao giờ chuyển tiếp giá trị do client tự gửi chưa kiểm.
 */
public final class ActAsTenant {

    public static final String HEADER = PermissionInterceptor.TENANT_HEADER;
    static final String ATTRIBUTE = ActAsTenant.class.getName() + ".TENANT";

    private ActAsTenant() {
    }

    /** UUID (dạng chuỗi) của tenant đích; rỗng khi request không phải platform admin hành động thay tenant. */
    public static Optional<String> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return Optional.empty();
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }
}
