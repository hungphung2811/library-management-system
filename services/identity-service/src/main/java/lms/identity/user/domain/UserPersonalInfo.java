package lms.identity.user.domain;

import java.time.LocalDate;

/**
 * Thông tin cá nhân tùy chọn của người dùng (toàn cục). Dữ liệu cá nhân: không ghi vào log, audit hay event.
 * Mọi trường có thể null. Giá trị được chuẩn hóa/kiểm tra khi dựng bằng {@link #of}.
 */
public record UserPersonalInfo(String phone, LocalDate dateOfBirth, Gender gender, String address, String avatarUrl,
                               String jobTitle, String employeeCode) {

    public static final UserPersonalInfo EMPTY = new UserPersonalInfo(null, null, null, null, null, null, null);

    /** Dựng có kiểm tra; vi phạm ném {@link InvalidUserException}. */
    public static UserPersonalInfo of(String phone, LocalDate dateOfBirth, Gender gender, String address,
                                      String avatarUrl, String jobTitle, String employeeCode, LocalDate today) {
        try {
            return new UserPersonalInfo(PersonalRules.phone(phone), PersonalRules.dateOfBirth(dateOfBirth, today), gender,
                    PersonalRules.text(address, 500, "Địa chỉ"), PersonalRules.httpUrl(avatarUrl, 500, "Ảnh đại diện"),
                    PersonalRules.text(jobTitle, 100, "Chức danh"), PersonalRules.text(employeeCode, 32, "Mã nhân viên"));
        } catch (IllegalArgumentException e) {
            throw new InvalidUserException(e.getMessage());
        }
    }

    public boolean isEmpty() {
        return this.equals(EMPTY);
    }

    /** Trường của {@code patch} khác null thì ghi đè (chuỗi rỗng đã chuẩn hóa thành null nên không xóa được qua đây). */
    public UserPersonalInfo merge(UserPersonalInfo patch) {
        return new UserPersonalInfo(pick(patch.phone, phone), pick(patch.dateOfBirth, dateOfBirth),
                pick(patch.gender, gender), pick(patch.address, address), pick(patch.avatarUrl, avatarUrl),
                pick(patch.jobTitle, jobTitle), pick(patch.employeeCode, employeeCode));
    }

    /** Chỉ điền trường đang null bằng giá trị của {@code defaults} (seed: không đè dữ liệu đã có). */
    public UserPersonalInfo fillMissing(UserPersonalInfo defaults) {
        return defaults.merge(this);
    }

    private static <T> T pick(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
