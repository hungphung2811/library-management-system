package lms.circulation.policy.domain;

/** Nguồn giá gốc để tính đền bù: giá bìa của đầu sách (mặc định) hoặc giá mua của bản sao (fallback giá bìa nếu không có). */
public enum PriceSourceKind {
    COVER_PRICE, PURCHASE_PRICE
}
