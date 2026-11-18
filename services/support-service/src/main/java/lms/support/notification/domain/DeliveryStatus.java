package lms.support.notification.domain;

public enum DeliveryStatus {
    PENDING, SENDING, SENT, RETRY, DEAD, SKIPPED;

    /** Trạng thái kết thúc: không còn xử lý, địa chỉ người nhận trực tiếp bị xóa. */
    public boolean terminal() {
        return this == SENT || this == DEAD || this == SKIPPED;
    }
}
