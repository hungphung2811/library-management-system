package lms.security.permission;

/**
 * Mô tả một khóa quyền do endpoint sinh ra. Cũng là payload gửi tới identity-service khi đăng ký khóa.
 *
 * @param service     tên service chứa endpoint
 * @param key         khóa quyền, ví dụ {@code BorrowingController/borrow} hoặc customKey {@code BORROW_BOOK}
 * @param source      {@code AUTO} (tự sinh) hoặc {@code CUSTOM} (BE đặt bằng {@link RequirePermission})
 * @param httpMethod  HTTP method của lần gọi đầu tiên thấy khóa này
 * @param pathPattern mẫu đường dẫn của endpoint, ví dụ {@code /api/borrowings/{id}/returns}
 * @param description mô tả cho người quản trị
 */
public record EndpointDescriptor(String service, String key, String source, String httpMethod,
                                 String pathPattern, String description) {

    public static final String REGISTRATION_PATH = "/internal/permissions/registrations";
    public static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";
}
