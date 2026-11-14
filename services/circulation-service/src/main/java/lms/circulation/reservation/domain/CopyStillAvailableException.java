package lms.circulation.reservation.domain;

/** QĐ5: chỉ đặt trước được đầu sách đã hết bản sao sẵn có. */
public class CopyStillAvailableException extends RuntimeException {
    public CopyStillAvailableException() {
        super("Đầu sách còn bản sao sẵn có, hãy mượn trực tiếp tại quầy");
    }
}
