package lms.security.permission;

/**
 * Bảo đảm một khóa quyền đã được lưu vào danh mục permission trong DB (do identity-service sở hữu).
 * Phải không chặn request và không bao giờ ném exception.
 */
public interface PermissionKeyRegistrar {

    void ensureRegistered(EndpointDescriptor descriptor);
}
