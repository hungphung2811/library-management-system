package lms.catalog.inventory.domain;

/** Ghi hàng loạt bản sao vi phạm ràng buộc duy nhất (trùng mã vạch do ghi đồng thời). Cả lô phải rollback. */
public class CopyBatchConflictException extends RuntimeException {

    public CopyBatchConflictException(Throwable cause) {
        super("Ghi hàng loạt bản sao bị xung đột khóa", cause);
    }
}
