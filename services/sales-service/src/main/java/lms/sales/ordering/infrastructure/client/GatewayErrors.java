package lms.sales.ordering.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLException;

/**
 * Phân loại lỗi kỹ thuật khi gọi cổng thành {@code errorKind} của log tích hợp (ADR-0029 mục 4.1). Chỉ dùng kiểu
 * exception và mã HTTP: không bao giờ đọc {@code getMessage()} (thông điệp của HttpClient có thể chứa URL kèm mã tham chiếu).
 */
final class GatewayErrors {

    static final String TIMEOUT = "TIMEOUT";
    static final String CONNECT = "CONNECT";
    static final String DNS = "DNS";
    static final String TLS = "TLS";
    static final String HTTP_AUTH = "HTTP_AUTH";
    static final String HTTP_RATE_LIMIT = "HTTP_RATE_LIMIT";
    static final String HTTP_4XX = "HTTP_4XX";
    static final String HTTP_5XX = "HTTP_5XX";
    static final String PARSE = "PARSE";
    static final String INTERRUPTED = "INTERRUPTED";
    static final String UNKNOWN = "UNKNOWN";

    private GatewayErrors() {
    }

    /**
     * Duyệt cả chuỗi nguyên nhân rồi chọn theo thứ tự ưu tiên TIMEOUT, DNS, TLS, CONNECT, PARSE, INTERRUPTED: HttpClient
     * bọc lỗi phân giải tên trong {@code ConnectException}, nên phải thấy nguyên nhân sâu trước khi kết luận CONNECT.
     */
    static String kindOf(Throwable error) {
        boolean timeout = false;
        boolean dns = false;
        boolean tls = false;
        boolean connect = false;
        boolean parse = false;
        boolean interrupted = false;
        Throwable t = error;
        for (int i = 0; t != null && i < 16; i++) {
            timeout |= t instanceof HttpTimeoutException; // gồm cả HttpConnectTimeoutException
            dns |= t instanceof UnknownHostException || t instanceof UnresolvedAddressException;
            tls |= t instanceof SSLException;
            connect |= t instanceof ConnectException;
            parse |= t instanceof JsonProcessingException;
            interrupted |= t instanceof InterruptedException;
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        if (timeout) {
            return TIMEOUT;
        }
        if (dns) {
            return DNS;
        }
        if (tls) {
            return TLS;
        }
        if (connect) {
            return CONNECT;
        }
        if (parse) {
            return PARSE;
        }
        return interrupted ? INTERRUPTED : UNKNOWN;
    }

    static String kindOfStatus(int status) {
        if (status == 401 || status == 403) {
            return HTTP_AUTH;
        }
        if (status == 429) {
            return HTTP_RATE_LIMIT;
        }
        if (status >= 500) {
            return HTTP_5XX;
        }
        return HTTP_4XX;
    }
}
