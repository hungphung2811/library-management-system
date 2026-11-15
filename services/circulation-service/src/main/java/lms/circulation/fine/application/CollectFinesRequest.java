package lms.circulation.fine.application;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CollectFinesRequest(@NotNull UUID readerId) {
}
