package lms.sales.invoicing.domain;

/** OPEN: chờ xuất. FULFILLED: đã có hóa đơn. WITHDRAWN: dành cho rút yêu cầu (chưa có thao tác). */
public enum RequestStatus {
    OPEN, FULFILLED, WITHDRAWN
}
