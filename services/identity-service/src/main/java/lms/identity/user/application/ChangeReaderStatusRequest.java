package lms.identity.user.application;

import lms.identity.user.domain.ReaderStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeReaderStatusRequest(@NotNull ReaderStatus status) {
}
