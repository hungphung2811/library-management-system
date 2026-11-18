package lms.sales.invoicing.application;

/** Dựng bản in hóa đơn. Cài đặt hiện có: HTML in được; chỗ để thêm PDF sau (ADR-0030, phương án đã hoãn). */
public interface InvoiceRendererPort {

    Rendered render(InvoiceView invoice);

    /** {@code contentType} đầy đủ (ví dụ "text/html;charset=UTF-8"). */
    record Rendered(String contentType, byte[] body) {
    }
}
