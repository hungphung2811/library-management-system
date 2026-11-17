package lms.sales.shared.domain;

/**
 * Adapter ném khi vi phạm UNIQUE (tenant_id, client_request_id) lúc lưu chứng từ (hai request trùng chạy đồng thời).
 * Use case bắt lại và trả chứng từ đã tạo.
 */
public class DuplicateClientRequestException extends RuntimeException {
    public DuplicateClientRequestException() {
        super("clientRequestId đã được dùng");
    }
}
