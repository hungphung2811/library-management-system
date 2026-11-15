package lms.circulation.borrowing.application;

/** Service phụ thuộc (identity, catalog) không trả lời được; thao tác bị hủy, không đổi dữ liệu. */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String service, Throwable cause) {
        super("Không gọi được " + service + ", thử lại sau", cause);
    }

    public DependencyUnavailableException(String message) {
        super(message);
    }
}
