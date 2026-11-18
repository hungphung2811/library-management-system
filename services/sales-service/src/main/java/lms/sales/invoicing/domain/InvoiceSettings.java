package lms.sales.invoicing.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Cấu hình hóa đơn theo tenant (tạo lười: chưa lưu thì {@link #defaults}, version 0, chưa đủ để phát hành). Người bán
 * cần tên pháp lý, MST đúng định dạng và địa chỉ; mẫu số tối đa 6 ký tự, ký hiệu tối đa 8 ký tự (chữ, số, gạch chéo)
 * để tên bộ đếm số hóa đơn vừa 30 ký tự. Đổi cấu hình không đổi hóa đơn đã phát hành (snapshot).
 */
public final class InvoiceSettings {

    public static final String DEFAULT_TEMPLATE_CODE = "1";
    private static final Pattern TEMPLATE = Pattern.compile("^[0-9A-Za-z/]{1,6}$");
    private static final Pattern SERIES = Pattern.compile("^[0-9A-Za-z/]{1,8}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UUID tenantId;
    private final String sellerLegalName;
    private final String sellerTaxCode;
    private final String sellerAddress;
    private final String sellerPhone;
    private final String sellerEmail;
    private final String templateCode;
    private final String series;
    private final boolean autoIssueOnCompletion;
    private final long version;
    private final Instant updatedAt;
    private final UUID updatedBy;

    private InvoiceSettings(UUID tenantId, String sellerLegalName, String sellerTaxCode, String sellerAddress,
                            String sellerPhone, String sellerEmail, String templateCode, String series,
                            boolean autoIssueOnCompletion, long version, Instant updatedAt, UUID updatedBy) {
        this.tenantId = tenantId;
        this.sellerLegalName = sellerLegalName;
        this.sellerTaxCode = sellerTaxCode;
        this.sellerAddress = sellerAddress;
        this.sellerPhone = sellerPhone;
        this.sellerEmail = sellerEmail;
        this.templateCode = templateCode;
        this.series = series;
        this.autoIssueOnCompletion = autoIssueOnCompletion;
        this.version = version;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static InvoiceSettings defaults(UUID tenantId) {
        return new InvoiceSettings(tenantId, null, null, null, null, null, DEFAULT_TEMPLATE_CODE, null, false, 0,
                null, null);
    }

    /** Dựng lại từ lưu trữ (đã validate lúc ghi). */
    public static InvoiceSettings restore(UUID tenantId, String sellerLegalName, String sellerTaxCode,
                                          String sellerAddress, String sellerPhone, String sellerEmail,
                                          String templateCode, String series, boolean autoIssueOnCompletion,
                                          long version, Instant updatedAt, UUID updatedBy) {
        return new InvoiceSettings(tenantId, sellerLegalName, sellerTaxCode, sellerAddress, sellerPhone,
                sellerEmail, templateCode, series, autoIssueOnCompletion, version, updatedAt, updatedBy);
    }

    /** Bản mới đã kiểm (cùng version; adapter tăng version khi lưu). */
    public InvoiceSettings update(String legalName, String taxCode, String address, String phone, String email,
                                  String newTemplateCode, String newSeries, boolean autoIssue, Instant now,
                                  UUID actor) {
        String name = required(legalName, 255, "Tên pháp lý người bán");
        String tax = TaxCode.seller(taxCode);
        String addr = required(address, 500, "Địa chỉ người bán");
        String tel = optional(phone, 30, "Điện thoại");
        String mail = optional(email, 255, "Email");
        if (mail != null && !EMAIL.matcher(mail).matches()) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS, "Email người bán không hợp lệ");
        }
        String tpl = newTemplateCode == null ? "" : newTemplateCode.trim();
        String ser = newSeries == null ? "" : newSeries.trim();
        if (!TEMPLATE.matcher(tpl).matches()) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS, "Mẫu số gồm 1 đến 6 chữ cái, chữ số hoặc dấu gạch chéo");
        }
        if (!SERIES.matcher(ser).matches()) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS,
                    "Ký hiệu hóa đơn gồm 1 đến 8 chữ cái, chữ số hoặc dấu gạch chéo");
        }
        return new InvoiceSettings(tenantId, name, tax, addr, tel, mail, tpl, ser, autoIssue, version, now, actor);
    }

    /** Đủ để phát hành: có tên pháp lý, MST, địa chỉ, mẫu số và ký hiệu. */
    public boolean isComplete() {
        return sellerLegalName != null && sellerTaxCode != null && sellerAddress != null && templateCode != null
                && series != null;
    }

    /** Snapshot người bán; chưa đủ cấu hình thì 409 INVOICE_SETTINGS_INCOMPLETE. */
    public SellerInfo requireSeller() {
        if (!isComplete()) {
            throw new SalesException(ErrorCode.INVOICE_SETTINGS_INCOMPLETE,
                    "Chưa cấu hình hóa đơn: cần tên pháp lý, mã số thuế, địa chỉ, mẫu số và ký hiệu");
        }
        return new SellerInfo(sellerLegalName, sellerTaxCode, sellerAddress, sellerPhone, sellerEmail);
    }

    private static String required(String value, int max, String label) {
        String v = optional(value, max, label);
        if (v == null) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS, label + " không được để trống");
        }
        return v;
    }

    private static String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS, label + " tối đa " + max + " ký tự");
        }
        return v;
    }

    public UUID tenantId() { return tenantId; }
    public String sellerLegalName() { return sellerLegalName; }
    public String sellerTaxCode() { return sellerTaxCode; }
    public String sellerAddress() { return sellerAddress; }
    public String sellerPhone() { return sellerPhone; }
    public String sellerEmail() { return sellerEmail; }
    public String templateCode() { return templateCode; }
    public String series() { return series; }
    public boolean autoIssueOnCompletion() { return autoIssueOnCompletion; }
    public long version() { return version; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
}
