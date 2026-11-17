package lms.api.catalog.dto;

import java.util.UUID;

/** Đổi vị trí kệ của bản sao; shelfLocationId null = gỡ vị trí. */
public record MoveCopyShelfLocationRequest(UUID shelfLocationId) {
}
