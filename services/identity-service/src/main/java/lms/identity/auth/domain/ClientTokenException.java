package lms.identity.auth.domain;

/**
 * Lỗi của token endpoint OAuth2 client_credentials theo RFC 6749 mục 5.2. {@code invalid_client} là MỘT mã chung cho mọi
 * lý do xác thực client thất bại (không lộ client_id có tồn tại hay không, lý do cụ thể); thông điệp là hằng, không chứa
 * dữ liệu do client gửi.
 */
public class ClientTokenException extends RuntimeException {

    public enum Error {
        INVALID_REQUEST("invalid_request", 400, "Yêu cầu không hợp lệ"),
        INVALID_CLIENT("invalid_client", 401, "Xác thực client thất bại"),
        UNSUPPORTED_GRANT_TYPE("unsupported_grant_type", 400, "Chỉ hỗ trợ grant_type=client_credentials"),
        INVALID_SCOPE("invalid_scope", 400, "Scope không hợp lệ");

        private final String code;
        private final int status;
        private final String description;

        Error(String code, int status, String description) {
            this.code = code;
            this.status = status;
            this.description = description;
        }

        public String code() { return code; }

        public int status() { return status; }

        public String description() { return description; }
    }

    private final Error error;

    public ClientTokenException(Error error) {
        super(error.code());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
