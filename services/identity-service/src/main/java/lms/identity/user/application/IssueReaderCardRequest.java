package lms.identity.user.application;

import lms.identity.user.domain.Gender;
import lms.identity.user.domain.ReaderType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * temporaryPassword (tùy chọn): tạo tài khoản online cho độc giả cùng lúc cấp thẻ; username mặc định là số thẻ.
 * Các trường từ phone trở xuống là thông tin cá nhân/học vụ tùy chọn.
 */
public record IssueReaderCardRequest(
        @NotBlank @Size(max = 32) String cardNo,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull ReaderType readerType,
        @NotNull LocalDate cardExpiryDate,
        @Size(min = 3, max = 100) String username,
        @Size(min = AccountCredentialPort.MIN_PASSWORD_LENGTH, max = 128) String temporaryPassword,
        @Size(max = 20) @Pattern(regexp = "^[0-9+ \\-]*$", message = "chỉ gồm chữ số, dấu cộng, dấu gạch ngang và khoảng trắng") String phone,
        @Past LocalDate dateOfBirth,
        Gender gender,
        @Size(max = 500) String address,
        @Size(max = 150) String faculty,
        @Size(max = 50) String className,
        @Size(max = 32) String memberCode,
        LocalDate cardIssuedDate) {

    public IssueReaderCardRequest(String cardNo, String fullName, String email, ReaderType readerType,
                                  LocalDate cardExpiryDate, String username, String temporaryPassword) {
        this(cardNo, fullName, email, readerType, cardExpiryDate, username, temporaryPassword,
                null, null, null, null, null, null, null, null);
    }
}
