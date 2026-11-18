package lms.circulation.borrowing.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Ghi nhận tìm lại sách đã báo mất. Chỉ định bản sao bằng bookCopyId hoặc barcode. restoreStatus: kết quả kiểm tra
 * tình trạng (AVAILABLE về kho, DAMAGED còn hỏng). refundMethod: CASH hoặc BANK_TRANSFER.
 */
public record ReportFoundRequest(UUID bookCopyId, String barcode, @NotNull @Pattern(regexp = "AVAILABLE|DAMAGED") String restoreStatus,
                                 @Size(max = 500) String checkNote, @NotNull @Pattern(regexp = "CASH|BANK_TRANSFER") String refundMethod,
                                 @Size(max = 100) String referenceCode) {
}
