package lms.identity.user.domain;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Người dùng (hồ sơ định danh, toàn cục): tên đăng nhập, họ tên, email, trạng thái. Mật khẩu và phiên đăng nhập do context
 * auth giữ (credential cùng userId); VÔ HIỆU HÓA ở đây chặn việc cấp token mới (xem TokenService). Phòng ban là dữ liệu của
 * tenant nên nằm ở membership (tenant_memberships.department_id), không ở đây.
 */
public class User {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,100}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UUID id;
    private final String username;
    private String fullName;
    private String email;
    private UserStatus status;

    private User(UUID id, String username, String fullName, String email, UserStatus status) {
        if (id == null) throw new InvalidUserException("id không được null");
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) {
            throw new InvalidUserException("Tên đăng nhập gồm chữ thường, số, '.', '_', '-', dài 3-100 ký tự");
        }
        if (status == null) throw new InvalidUserException("Trạng thái không được null");
        this.id = id;
        this.username = u;
        this.status = status;
        applyProfile(fullName, email);
    }

    private void applyProfile(String newFullName, String newEmail) {
        if (newFullName == null || newFullName.isBlank()) throw new InvalidUserException("Họ tên không được để trống");
        if (newFullName.trim().length() > 255) throw new InvalidUserException("Họ tên tối đa 255 ký tự");
        String e = normalizeEmail(newEmail);
        if (e.length() > 255 || !EMAIL.matcher(e).matches()) throw new InvalidUserException("Email không hợp lệ");
        this.fullName = newFullName.trim();
        this.email = e;
    }

    private static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** id null thì sinh mới; truyền id có sẵn để gắn với credential đã tồn tại (ví dụ tài khoản quản trị khởi tạo). */
    public static User create(UUID id, String username, String fullName, String email) {
        return new User(id == null ? UUID.randomUUID() : id, username, fullName, email, UserStatus.ACTIVE);
    }

    public static User restore(UUID id, String username, String fullName, String email, UserStatus status) {
        return new User(id, username, fullName, email, status);
    }

    public void update(String newFullName, String newEmail) {
        applyProfile(newFullName, newEmail);
    }

    /** Họ tên và email (sau khi chuẩn hóa như lúc lưu) có khác giá trị hiện tại không. */
    public boolean profileDiffers(String newFullName, String newEmail) {
        String name = newFullName == null ? "" : newFullName.trim();
        return !name.equals(fullName) || !normalizeEmail(newEmail).equals(email);
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }

    public void enable() {
        this.status = UserStatus.ACTIVE;
    }

    public boolean isDisabled() {
        return status == UserStatus.DISABLED;
    }

    public UUID id() { return id; }
    public String username() { return username; }
    public String fullName() { return fullName; }
    public String email() { return email; }
    public UserStatus status() { return status; }
}
