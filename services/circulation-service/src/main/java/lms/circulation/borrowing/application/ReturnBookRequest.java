package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.ItemCondition;
import java.util.UUID;

/** Chỉ định bản sao bằng bookCopyId hoặc barcode (máy quét); condition mặc định GOOD, chỉ nhận GOOD hoặc DAMAGED. */
public record ReturnBookRequest(UUID bookCopyId, String barcode, ItemCondition condition) {
}
