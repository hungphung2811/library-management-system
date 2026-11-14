package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record CopyDetailsResponse(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                                  BigDecimal coverPrice) {

    public static CopyDetailsResponse from(CopyDetails d) {
        return new CopyDetailsResponse(d.id(), d.titleId(), d.titleName(), d.barcode(), d.status(), d.coverPrice());
    }
}
