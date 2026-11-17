package lms.identity.user.application;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Tra tên người dùng theo lô (ADR-0018 mục 2.3); tối đa {@value LookupUsersUseCase#MAX_IDS} id mỗi lần. */
public record LookupUsersRequest(
        @NotEmpty @Size(max = LookupUsersUseCase.MAX_IDS) List<@jakarta.validation.constraints.NotNull UUID> ids) {
}
