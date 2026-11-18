package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.CopyAcquisition;
import java.util.UUID;

public record AddCopyCommand(UUID titleId, String barcode, UUID shelfLocationId, CopyAcquisition acquisition) {

    public AddCopyCommand {
        acquisition = acquisition == null ? CopyAcquisition.EMPTY : acquisition;
    }

    public AddCopyCommand(UUID titleId, String barcode, UUID shelfLocationId) {
        this(titleId, barcode, shelfLocationId, null);
    }

    public AddCopyCommand(UUID titleId, String barcode) {
        this(titleId, barcode, null, null);
    }
}
