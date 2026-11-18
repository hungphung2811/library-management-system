package lms.circulation.fine.domain;

import java.math.BigDecimal;

/**
 * Các giá có thể dùng làm giá gốc của một bản sao (cổng PriceSource trả về). Giá null hoặc không dương coi như không
 * có. Mô hình sách ba tầng sau này chỉ cần adapter trả thêm giá theo ấn bản, không đổi cách chọn nguồn ở đây.
 */
public record PriceCandidates(BigDecimal coverPrice, BigDecimal purchasePrice) {

    /** Chọn giá theo nguồn ưu tiên; nguồn ưu tiên không có giá thì rơi về giá bìa và đánh dấu fallback. */
    public PriceQuote select(PriceSourceKind preferred) {
        if (preferred == PriceSourceKind.PURCHASE_PRICE) {
            if (purchasePrice != null && purchasePrice.signum() > 0) {
                return new PriceQuote(purchasePrice, PriceSourceKind.PURCHASE_PRICE, false);
            }
            return new PriceQuote(requireCover(), PriceSourceKind.COVER_PRICE, true);
        }
        return new PriceQuote(requireCover(), PriceSourceKind.COVER_PRICE, false);
    }

    private BigDecimal requireCover() {
        if (coverPrice == null || coverPrice.signum() < 0) throw new InvalidFineException("Giá bìa không hợp lệ");
        return coverPrice;
    }
}
