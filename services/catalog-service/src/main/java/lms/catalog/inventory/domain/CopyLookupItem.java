package lms.catalog.inventory.domain;

import java.util.UUID;

/** Thông tin tối thiểu của bản sao cho service khác tra theo lô (kèm tên và ISBN đầu sách). */
public record CopyLookupItem(UUID id, UUID titleId, String titleName, String isbn, String barcode,
                             CopyStatus status) {
}
