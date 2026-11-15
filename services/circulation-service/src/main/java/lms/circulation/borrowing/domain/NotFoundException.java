package lms.circulation.borrowing.domain;

/** Không tìm thấy đối tượng liên quan (độc giả, bản sao, lượt mượn đang mở). code dùng làm mã lỗi trả về. */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
