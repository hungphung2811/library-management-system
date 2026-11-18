package lms.sales.invoicing.application;

import java.time.Instant;
import lms.sales.invoicing.domain.InvoiceSettings;

/** Cấu hình hóa đơn; {@code complete} cho biết đã đủ để phát hành (tên pháp lý, MST, địa chỉ, mẫu số, ký hiệu). */
public record InvoiceSettingsView(String sellerLegalName, String sellerTaxCode, String sellerAddress,
                                  String sellerPhone, String sellerEmail, String templateCode, String series,
                                  boolean autoIssueOnCompletion, boolean complete, long version,
                                  Instant updatedAt) {

    public static InvoiceSettingsView from(InvoiceSettings s) {
        return new InvoiceSettingsView(s.sellerLegalName(), s.sellerTaxCode(), s.sellerAddress(), s.sellerPhone(),
                s.sellerEmail(), s.templateCode(), s.series(), s.autoIssueOnCompletion(), s.isComplete(),
                s.version(), s.updatedAt());
    }
}
