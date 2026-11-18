package lms.sales.shift.application;

import java.util.List;

/**
 * Báo cáo ca theo phương thức thanh toán (ADR-0030 D3.8). Mọi phương thức luôn có một dòng (kể cả 0) để FE hiển thị cố
 * định: CASH, CARD, BANK_TRANSFER, QR, MOMO, SEPAY. {@code afterClose} là phần phát sinh sau thời điểm chốt (đã nằm
 * trong tổng, không ảnh hưởng tiền mặt dự kiến đã chụp).
 */
public record ShiftReport(long orderCount, long grandTotal, long discountTotal, List<MethodLine> methods,
                          long collectedTotal, long refundedTotal, long netTotal) {

    public ShiftReport {
        methods = List.copyOf(methods);
    }

    public record MethodLine(String method, long paymentCount, long collected, long refundCount, long refunded,
                             long net, long paymentCountAfterClose, long collectedAfterClose,
                             long refundCountAfterClose, long refundedAfterClose) {
    }

    /** Thu tiền mặt của ca (0 nếu không có). */
    public long cashCollected() {
        return line("CASH") == null ? 0 : line("CASH").collected();
    }

    /** Hoàn tiền mặt của ca (0 nếu không có). */
    public long cashRefunded() {
        return line("CASH") == null ? 0 : line("CASH").refunded();
    }

    public MethodLine line(String method) {
        return methods.stream().filter(m -> m.method().equals(method)).findFirst().orElse(null);
    }
}
