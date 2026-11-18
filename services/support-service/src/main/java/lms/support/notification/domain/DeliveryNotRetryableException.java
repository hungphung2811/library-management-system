package lms.support.notification.domain;

public class DeliveryNotRetryableException extends RuntimeException {

    public DeliveryNotRetryableException() {
        super("Chỉ gửi lại được lượt gửi ở trạng thái DEAD");
    }
}
