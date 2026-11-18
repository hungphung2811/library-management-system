package lms.catalog.catalog.domain;

/** Ghi hàng loạt danh mục vi phạm ràng buộc duy nhất (người khác vừa tạo cùng tên). Cả lô phải rollback. */
public class ReferenceBatchConflictException extends RuntimeException {

    public ReferenceBatchConflictException(Throwable cause) {
        super("Ghi hàng loạt danh mục bị xung đột khóa", cause);
    }
}
