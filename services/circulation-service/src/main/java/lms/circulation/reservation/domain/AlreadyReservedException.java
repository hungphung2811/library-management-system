package lms.circulation.reservation.domain;

/** QĐ5: mỗi độc giả chỉ có tối đa một đặt trước còn hiệu lực cho mỗi đầu sách. */
public class AlreadyReservedException extends RuntimeException {
    public AlreadyReservedException() {
        super("Độc giả đã có đặt trước còn hiệu lực cho đầu sách này");
    }
}
