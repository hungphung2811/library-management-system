package lms.sales.invoicing.domain;

/**
 * Loại hóa đơn. ORIGINAL: hóa đơn gốc của đơn. REPLACEMENT: thay thế hóa đơn gốc đã hủy (có originalInvoiceId).
 * ADJUSTMENT_DECREASE: điều chỉnh giảm do trả hàng (có originalInvoiceId và returnId). Điều chỉnh tăng ngoài phạm vi.
 */
public enum InvoiceKind {
    ORIGINAL, REPLACEMENT, ADJUSTMENT_DECREASE;

    /** Hóa đơn "chính" của đơn (mỗi đơn tối đa một cái còn hiệu lực). */
    public boolean isPrimary() {
        return this == ORIGINAL || this == REPLACEMENT;
    }
}
