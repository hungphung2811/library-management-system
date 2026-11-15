package lms.api.circulation.dto;

import java.util.UUID;

/** Báo mất bản sao đang mượn, chỉ định bằng bookCopyId hoặc barcode. */
public record ReportLostRequest(UUID bookCopyId, String barcode) {
}
