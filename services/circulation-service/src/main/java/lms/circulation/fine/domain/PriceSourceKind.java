package lms.circulation.fine.domain;

/** Nguồn giá gốc để tính đền bù hỏng/mất. Mở rộng được (ví dụ giá theo ấn bản) mà không đổi logic tính phạt. */
public enum PriceSourceKind {
    COVER_PRICE, PURCHASE_PRICE
}
