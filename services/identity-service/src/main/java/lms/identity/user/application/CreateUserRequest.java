package lms.identity.user.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import lms.identity.user.domain.Gender;
import lms.identity.user.domain.UserPersonalInfo;
import java.time.LocalDate;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Tạo người dùng trong tenant hiện tại. password có giá trị: tạo luôn credential (mustChangePassword = mật khẩu tạm).
 * password null: chỉ tạo hồ sơ, id tùy chọn để gắn với credential đã tạo bằng POST /internal/auth/credentials (credential
 * đó phải đã là thành viên tenant hiện tại). departmentId là phòng ban trong tenant hiện tại (ghi vào membership).
 */
public record CreateUserRequest(
        UUID id,
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        UUID departmentId,
        @Size(min = AccountCredentialPort.MIN_PASSWORD_LENGTH, max = 128) String password,
        Boolean mustChangePassword,
        @Size(max = 20) @Pattern(regexp = "^[0-9+ \\-]*$", message = "chỉ gồm chữ số, dấu cộng, dấu gạch ngang và khoảng trắng") String phone,
        @Past LocalDate dateOfBirth,
        Gender gender,
        @Size(max = 500) String address,
        @Size(max = 500) @Pattern(regexp = "^(?i)(https?://\\S+)?$", message = "phải là địa chỉ http/https") String avatarUrl,
        @Size(max = 100) String jobTitle,
        @Size(max = 32) String employeeCode) {

    /** Không có thông tin cá nhân. */
    public CreateUserRequest(UUID id, String username, String fullName, String email, UUID departmentId,
                             String password, Boolean mustChangePassword) {
        this(id, username, fullName, email, departmentId, password, mustChangePassword,
                null, null, null, null, null, null, null);
    }

    /** Chỉ tạo hồ sơ (không có credential). */
    public CreateUserRequest(UUID id, String username, String fullName, String email, UUID departmentId) {
        this(id, username, fullName, email, departmentId, null, null);
    }

    /** Thông tin cá nhân (đã chuẩn hóa/kiểm tra); ngày hiện tại do người gọi truyền để use case không đọc đồng hồ ở đây. */
    public UserPersonalInfo personalInfo(LocalDate today) {
        return UserPersonalInfo.of(phone, dateOfBirth, gender, address, avatarUrl, jobTitle, employeeCode, today);
    }
}
