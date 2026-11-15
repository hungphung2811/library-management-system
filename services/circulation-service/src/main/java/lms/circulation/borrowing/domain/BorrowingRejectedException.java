package lms.circulation.borrowing.domain;

/** Lập phiếu bị từ chối vì vi phạm điều kiện nghiệp vụ (QĐ2, tình trạng bản sao, ...). code dùng làm mã lỗi trả về. */
public class BorrowingRejectedException extends RuntimeException {

    private final String code;

    public BorrowingRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
