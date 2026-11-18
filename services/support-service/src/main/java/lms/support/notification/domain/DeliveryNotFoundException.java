package lms.support.notification.domain;

public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException() {
        super("Không tìm thấy lượt gửi");
    }
}
