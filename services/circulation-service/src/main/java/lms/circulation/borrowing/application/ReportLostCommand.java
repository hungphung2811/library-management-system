package lms.circulation.borrowing.application;

import java.util.UUID;

public record ReportLostCommand(UUID bookCopyId, String barcode) {
}
