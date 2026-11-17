package lms.api.catalog.dto;

import jakarta.validation.constraints.NotNull;

/** Bật (true) hoặc ngừng sử dụng (false) một mục danh mục. */
public record SetReferenceStatusRequest(@NotNull Boolean active) {
}
