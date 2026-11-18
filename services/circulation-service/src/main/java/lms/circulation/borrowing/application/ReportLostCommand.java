package lms.circulation.borrowing.application;

import java.util.List;
import java.util.UUID;

public record ReportLostCommand(UUID bookCopyId, String barcode, String damageNote, List<String> evidenceUrls) {

    public ReportLostCommand(UUID bookCopyId, String barcode) {
        this(bookCopyId, barcode, null, null);
    }
}
