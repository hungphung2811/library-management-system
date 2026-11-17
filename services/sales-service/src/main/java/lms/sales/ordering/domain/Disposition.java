package lms.sales.ordering.domain;

/** Cách xử lý hàng trả: nhập lại tồn hoặc ghi hao hụt (hàng hỏng). */
public enum Disposition {
    RESTOCK,
    WRITE_OFF
}
