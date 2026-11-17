package lms.catalog.inventory.application;

import java.util.UUID;

public record AddCopyCommand(UUID titleId, String barcode, UUID shelfLocationId) {

    public AddCopyCommand(UUID titleId, String barcode) {
        this(titleId, barcode, null);
    }
}
