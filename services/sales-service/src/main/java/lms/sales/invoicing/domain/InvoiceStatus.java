package lms.sales.invoicing.domain;

/** Vòng đời hóa đơn: DRAFT (chưa có số) sang ISSUED (có số, bất biến) sang CANCELLED (đã hủy, vẫn giữ để đối chiếu). */
public enum InvoiceStatus {
    DRAFT, ISSUED, CANCELLED
}
