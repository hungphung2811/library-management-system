package lms.sales.invoicing.application;

import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceKind;

/**
 * Cổng phát hành hóa đơn (ADR-0030 D1). Cài đặt mặc định {@link InternalInvoiceIssuer}: chỉ cấp số nội bộ từ bộ đếm,
 * KHÔNG gửi đi đâu, nên hóa đơn CHƯA có giá trị pháp lý của hóa đơn điện tử. Sau này cắm nhà cung cấp HĐĐT bằng adapter
 * mới (không đổi use case). Lưu ý: khi có nhà cung cấp thật, lời gọi ra ngoài KHÔNG được nằm trong transaction giữ khóa
 * đơn: tách DRAFT (commit), gọi nhà cung cấp, rồi ISSUED (transaction mới); {@link IssuerResult} đã có chỗ cho kết quả.
 */
public interface InvoiceIssuerPort {

    IssuerResult issue(IssueRequest request);

    record IssueRequest(UUID tenantId, UUID invoiceId, InvoiceKind kind, String templateCode, String series,
                        int fiscalYear, long grandTotal) {
    }

    /**
     * {@code assignedNumber}: số hóa đơn do bên phát hành cấp (use case ưu tiên số này). {@code issuer}: mã nhà cung
     * cấp ("INTERNAL" cho nội bộ). Các trường external là mã tra cứu của nhà cung cấp (null với hóa đơn nội bộ).
     */
    record IssuerResult(String issuer, Long assignedNumber, String externalId, String externalStatus,
                        String externalLookupCode) {
    }
}
