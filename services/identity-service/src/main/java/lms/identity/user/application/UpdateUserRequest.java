package lms.identity.user.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lms.identity.user.domain.Gender;
import lms.identity.user.domain.UserPersonalInfo;

/**
 * Tên đăng nhập không đổi được. departmentId null nghĩa là bỏ phòng ban. Các trường thông tin cá nhân (phone ... employeeCode)
 * là tùy chọn: null hoặc vắng nghĩa là giữ nguyên giá trị hiện có, có giá trị thì ghi đè.
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        UUID departmentId,
        @Size(max = 20) @Pattern(regexp = "^[0-9+ \\-]*$", message = "chỉ gồm chữ số, dấu cộng, dấu gạch ngang và khoảng trắng") String phone,
        @Past LocalDate dateOfBirth,
        Gender gender,
        @Size(max = 500) String address,
        @Size(max = 500) @Pattern(regexp = "^(?i)(https?://\\S+)?$", message = "phải là địa chỉ http/https") String avatarUrl,
        @Size(max = 100) String jobTitle,
        @Size(max = 32) String employeeCode) {

    public UpdateUserRequest(String fullName, String email, UUID departmentId) {
        this(fullName, email, departmentId, null, null, null, null, null, null, null);
    }

    public UserPersonalInfo personalInfo(LocalDate today) {
        return UserPersonalInfo.of(phone, dateOfBirth, gender, address, avatarUrl, jobTitle, employeeCode, today);
    }
}
