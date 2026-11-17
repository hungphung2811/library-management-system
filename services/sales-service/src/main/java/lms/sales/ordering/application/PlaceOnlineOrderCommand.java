package lms.sales.ordering.application;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;

/** Khách đặt đơn online. {@code customerUserId} luôn lấy từ token, không nhận từ body. */
public record PlaceOnlineOrderCommand(UUID tenantId, UUID customerUserId, List<Line> lines, Fulfilment fulfilment,
                                      PaymentMethod paymentMethod, Recipient recipient, UUID clientRequestId) {

    public record Line(UUID titleId, int quantity) {
    }

    public PlaceOnlineOrderCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    @Override
    public String toString() { // không in người nhận
        return "PlaceOnlineOrderCommand[tenant=" + tenantId + ", lines=" + lines.size() + ", clientRequestId="
                + clientRequestId + "]";
    }
}
