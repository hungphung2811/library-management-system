package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.CopyStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeCopyStatusRequest(@NotNull CopyStatus status) {
}
