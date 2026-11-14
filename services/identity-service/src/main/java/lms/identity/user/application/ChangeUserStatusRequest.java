package lms.identity.user.application;

import lms.identity.user.domain.UserStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeUserStatusRequest(@NotNull UserStatus status) {
}
