package lms.identity.user.application;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lms.identity.user.domain.Gender;
import lms.identity.user.domain.ReaderPersonalInfo;

/** Sửa thông tin cá nhân/học vụ của độc giả. Trường null hoặc vắng nghĩa là giữ nguyên, có giá trị thì ghi đè. */
public record UpdateReaderProfileRequest(
        @Size(max = 20) @Pattern(regexp = "^[0-9+ \\-]*$", message = "chỉ gồm chữ số, dấu cộng, dấu gạch ngang và khoảng trắng") String phone,
        @Past LocalDate dateOfBirth,
        Gender gender,
        @Size(max = 500) String address,
        @Size(max = 150) String faculty,
        @Size(max = 50) String className,
        @Size(max = 32) String memberCode,
        LocalDate cardIssuedDate) {

    public ReaderPersonalInfo personalInfo(LocalDate today) {
        return ReaderPersonalInfo.of(phone, dateOfBirth, gender, address, faculty, className, memberCode,
                cardIssuedDate, today);
    }
}
