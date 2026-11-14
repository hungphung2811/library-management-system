package lms.identity.user.application;

import lms.identity.user.domain.ReaderType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record IssueReaderCardRequest(
        @NotBlank @Size(max = 32) String cardNo,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull ReaderType readerType,
        @NotNull LocalDate cardExpiryDate) {
}
