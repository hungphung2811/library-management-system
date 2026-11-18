package lms.catalog.inventory.application;

/** Ghi hàng loạt bản sao bị xung đột khóa (mã vạch trùng do ghi đồng thời); giao dịch của người gọi phải rollback. */
public class CopyWriteConflictException extends RuntimeException {

    public CopyWriteConflictException(Throwable cause) {
        super("Ghi hàng loạt bản sao bị xung đột khóa", cause);
    }
}
