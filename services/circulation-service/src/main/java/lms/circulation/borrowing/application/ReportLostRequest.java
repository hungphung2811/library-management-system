package lms.circulation.borrowing.application;

import java.util.UUID;

/** Chỉ định bản sao bị mất bằng bookCopyId hoặc barcode. */
public record ReportLostRequest(UUID bookCopyId, String barcode) {
}
