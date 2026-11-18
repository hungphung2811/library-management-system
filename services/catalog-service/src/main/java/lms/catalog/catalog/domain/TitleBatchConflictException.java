package lms.catalog.catalog.domain;

/** Ghi hàng loạt đầu sách vi phạm ràng buộc duy nhất (trùng ISBN do ghi đồng thời). Cả lô phải rollback. */
public class TitleBatchConflictException extends RuntimeException {

    public TitleBatchConflictException(Throwable cause) {
        super("Ghi hàng loạt đầu sách bị xung đột khóa", cause);
    }
}
