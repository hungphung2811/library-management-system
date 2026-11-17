package lms.sales.ordering.domain;

public enum OrderStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    READY_FOR_PICKUP,
    SHIPPING,
    COMPLETED,
    CANCELLED
}
