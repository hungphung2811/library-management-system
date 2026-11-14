package lms.api.catalog.dto;

import java.util.UUID;

public record CopyView(UUID id, UUID titleId, String barcode, String status) {
}
