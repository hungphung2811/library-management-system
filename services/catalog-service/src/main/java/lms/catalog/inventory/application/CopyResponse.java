package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.CopyStatus;
import java.util.UUID;

public record CopyResponse(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId,
                           UUID shelfLocationId, String shelfLocationCode) {

    /** Dạng không kèm vị trí kệ (tương thích cũ). */
    public CopyResponse(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId) {
        this(id, titleId, barcode, status, tenantId, null, null);
    }

    public static CopyResponse from(BookCopy copy) {
        return new CopyResponse(copy.id(), copy.titleId(), copy.barcode(), copy.status(), copy.tenantId(),
                copy.shelfLocationId(), null);
    }

    public static CopyResponse from(BookCopy copy, String shelfLocationCode) {
        return new CopyResponse(copy.id(), copy.titleId(), copy.barcode(), copy.status(), copy.tenantId(),
                copy.shelfLocationId(), shelfLocationCode);
    }
}
