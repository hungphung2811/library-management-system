package lms.api.circulation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReserveTitleRequest(@NotNull UUID titleId) {
}
