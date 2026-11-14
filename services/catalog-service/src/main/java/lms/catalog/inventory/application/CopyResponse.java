package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.CopyStatus;
import java.util.UUID;

public record CopyResponse(UUID id, UUID titleId, String barcode, CopyStatus status) {

    public static CopyResponse from(BookCopy copy) {
        return new CopyResponse(copy.id(), copy.titleId(), copy.barcode(), copy.status());
    }
}
