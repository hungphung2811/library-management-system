package lms.sales.ordering.application;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.Disposition;

/** Tạo phiếu trả. {@code lateReason} chỉ dùng ở luồng quá hạn (createLate). */
public record CreateReturnCommand(UUID tenantId, UUID actor, UUID orderId, List<Line> lines, String reason,
                                  String lateReason, UUID clientRequestId) {

    public record Line(UUID orderLineId, int quantity, Disposition disposition) {
    }

    public CreateReturnCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
