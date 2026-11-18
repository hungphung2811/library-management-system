package lms.sales.invoicing.application;

import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceNumbers;
import lms.sales.shared.application.DocumentNumberPort;

/**
 * Bên phát hành nội bộ: cấp số tuần tự theo (tenant, mẫu số, ký hiệu, năm) bằng bộ đếm {@code sales_counters} trong CÙNG
 * transaction với việc lưu hóa đơn (khóa dòng đếm đến hết transaction nên các lần phát hành tuần tự hóa, rollback không để
 * lỗ hổng số). KHÔNG gửi cho cơ quan thuế hay nhà cung cấp nào: hóa đơn nội bộ không thay thế hóa đơn điện tử.
 */
public class InternalInvoiceIssuer implements InvoiceIssuerPort {

    private final DocumentNumberPort numbers;

    public InternalInvoiceIssuer(DocumentNumberPort numbers) {
        this.numbers = numbers;
    }

    @Override
    public IssuerResult issue(IssueRequest request) {
        String counter = InvoiceNumbers.counterName(request.templateCode(), request.series(), request.fiscalYear());
        long number = numbers.nextValue(request.tenantId(), counter);
        return new IssuerResult(Invoice.ISSUER_INTERNAL, number, null, null, null);
    }
}
