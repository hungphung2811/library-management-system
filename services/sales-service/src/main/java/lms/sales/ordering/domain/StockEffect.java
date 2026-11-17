package lms.sales.ordering.domain;

/** Hệ quả tồn kho khi hủy đơn: nhả hàng đang giữ (reserved) hoặc nhập lại hàng đã rời kho (giao thất bại). */
public enum StockEffect {
    RELEASE,
    RESTOCK
}
