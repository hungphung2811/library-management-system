package lms.api.catalog.dto;

import java.util.UUID;

public record ShelfLocationView(UUID id, String code, String description, boolean active, UUID tenantId) {
}
