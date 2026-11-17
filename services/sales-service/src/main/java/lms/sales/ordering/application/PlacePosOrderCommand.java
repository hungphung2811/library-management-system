package lms.sales.ordering.application;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.PaymentMethod;

/**
 * Đơn POS một bước (thu ngân chỉ gửi khi đã nhận tiền/quẹt thẻ xong ngoài hệ thống).
 * Giảm giá dòng/đơn là null nếu không có. {@code exchangeOfReturnId} khác null là đơn đổi hàng (gắn phiếu trả). {@code userId} là người tạo (cũng là người thu).
 */
public record PlacePosOrderCommand(UUID tenantId, UUID userId, List<Line> lines, PaymentMethod paymentMethod,
                                   UUID customerUserId, String walkInName, String walkInPhone,
                                   Discount orderDiscount, UUID clientRequestId, UUID exchangeOfReturnId) {

    /** Đơn POS thường (không phải đổi hàng). */
    public PlacePosOrderCommand(UUID tenantId, UUID userId, List<Line> lines, PaymentMethod paymentMethod,
                                UUID customerUserId, String walkInName, String walkInPhone,
                                Discount orderDiscount, UUID clientRequestId) {
        this(tenantId, userId, lines, paymentMethod, customerUserId, walkInName, walkInPhone, orderDiscount,
                clientRequestId, null);
    }

    public record Line(UUID titleId, int quantity, Discount discount) {
    }

    public PlacePosOrderCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean hasDiscount() {
        return orderDiscount != null || lines.stream().anyMatch(l -> l.discount() != null);
    }

    @Override
    public String toString() { // không in tên/điện thoại khách
        return "PlacePosOrderCommand[tenant=" + tenantId + ", lines=" + lines.size() + ", clientRequestId="
                + clientRequestId + "]";
    }
}
