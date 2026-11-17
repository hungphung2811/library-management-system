package lms.identity.auth.application;

import java.util.UUID;

/** Role đăng ký mặc định của tenant hiện tại (cờ signup_default), do context accesscontrol đảm nhiệm. */
public interface SignupRolePort {

    /** Tenant hiện tại có role đăng ký mặc định hợp lệ (không phải admin) không. */
    boolean available();

    /** Gán role đăng ký mặc định cho người dùng; không có thì ném lỗi (người gọi đã kiểm {@link #available()}). */
    void assignTo(UUID userId);
}
