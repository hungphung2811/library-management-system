package lms.circulation.borrowing.application;

import java.util.UUID;

public record ReturnBookCommand(UUID borrowingId, UUID bookCopyId) {
}
