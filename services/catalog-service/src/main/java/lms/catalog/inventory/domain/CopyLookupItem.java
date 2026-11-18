package lms.catalog.inventory.domain;

import java.util.UUID;

/** Thông tin tối thiểu của bản sao cho service khác tra theo lô (kèm tên và ISBN đầu sách). */
public record CopyLookupItem(UUID id, UUID titleId, String titleName, String isbn, String barcode,
                             CopyStatus status, UUID workId) {

    /** Dạng 6 trường (chưa có workId, ADR-0026). */
    public CopyLookupItem(UUID id, UUID titleId, String titleName, String isbn, String barcode, CopyStatus status) {
        this(id, titleId, titleName, isbn, barcode, status, null);
    }
}
