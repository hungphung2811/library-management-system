package lms.catalog.inventory.application;

import java.util.UUID;
import lms.catalog.inventory.domain.CopyLookupItem;

/** Một phần tử kết quả tra lô bản sao: không lộ tenantId, giá, vị trí kệ. */
public record CopyLookupView(UUID id, UUID titleId, String titleName, String isbn, String barcode, String status) {

    public static CopyLookupView from(CopyLookupItem item) {
        return new CopyLookupView(item.id(), item.titleId(), item.titleName(), item.isbn(), item.barcode(),
                item.status().name());
    }
}
