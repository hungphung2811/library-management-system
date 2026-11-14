package lms.identity.user.application;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record RenewReaderCardRequest(@NotNull LocalDate newExpiryDate) {
}
