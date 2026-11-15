package lms.identity.user.application;

import lms.identity.user.domain.ReaderType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** temporaryPassword (tùy chọn): tạo tài khoản online cho độc giả cùng lúc cấp thẻ; username mặc định là số thẻ. */
public record IssueReaderCardRequest(
        @NotBlank @Size(max = 32) String cardNo,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull ReaderType readerType,
        @NotNull LocalDate cardExpiryDate,
        @Size(min = 3, max = 100) String username,
        @Size(min = AccountCredentialPort.MIN_PASSWORD_LENGTH, max = 128) String temporaryPassword) {
}
