package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.ItemCondition;
import java.util.List;
import java.util.UUID;

/**
 * borrowingId có thể null: khi đó tìm lượt mượn đang mở theo bản sao (UC07 bước 2). Bản sao chỉ định bằng id hoặc mã
 * vạch. damageLevelCode, damageNote, evidenceUrls chỉ hợp lệ khi condition là DAMAGED.
 */
public record ReturnBookCommand(UUID borrowingId, UUID bookCopyId, String barcode, ItemCondition condition,
                                String damageLevelCode, String damageNote, List<String> evidenceUrls) {

    public ReturnBookCommand(UUID borrowingId, UUID bookCopyId, String barcode, ItemCondition condition) {
        this(borrowingId, bookCopyId, barcode, condition, null, null, null);
    }
}
