package lms.sales.ordering.application;

/** Đường kích hoạt việc ghi nhận thanh toán: FE thăm dò cổng, webhook của cổng, hoặc nút giả lập trên màn POS. */
public enum PaymentAnomalySource {
    POLL, WEBHOOK, SIMULATE_BUTTON
}
