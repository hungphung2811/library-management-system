package lms.api.catalog.dto;

import jakarta.validation.constraints.NotNull;

public record ChangeCopyStatusRequest(@NotNull CopyStatus status) {
}
