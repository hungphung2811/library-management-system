package lms.identity.user.application;

import lms.identity.user.domain.ReaderType;
import java.time.LocalDate;
import java.util.UUID;

public record IssueReaderCardCommand(UUID userId, String cardNo, String fullName, String email,
                                     ReaderType readerType, LocalDate cardExpiryDate) {
}
