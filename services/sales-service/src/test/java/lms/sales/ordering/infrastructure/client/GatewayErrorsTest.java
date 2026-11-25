package lms.sales.ordering.infrastructure.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonParseException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/** Phân loại errorKind (ADR-0029 mục 4.1) chỉ theo kiểu exception và mã HTTP. */
class GatewayErrorsTest {

    @Test
    void phanLoaiTheoKieuException() {
        assertEquals("TIMEOUT", GatewayErrors.kindOf(new HttpTimeoutException("x")));
        assertEquals("TIMEOUT", GatewayErrors.kindOf(new HttpConnectTimeoutException("x")));
        assertEquals("CONNECT", GatewayErrors.kindOf(new ConnectException("x")));
        assertEquals("DNS", GatewayErrors.kindOf(new UnknownHostException("x")));
        assertEquals("DNS", GatewayErrors.kindOf(new UnresolvedAddressException()));
        assertEquals("TLS", GatewayErrors.kindOf(new SSLHandshakeException("x")));
        assertEquals("PARSE", GatewayErrors.kindOf(new JsonParseException(null, "x")));
        assertEquals("INTERRUPTED", GatewayErrors.kindOf(new InterruptedException("x")));
        assertEquals("UNKNOWN", GatewayErrors.kindOf(new IOException("x")));
        assertEquals("UNKNOWN", GatewayErrors.kindOf(null));
    }

    @Test
    void duyetChuoiNguyenNhan() {
        assertEquals("TIMEOUT", GatewayErrors.kindOf(new RuntimeException("bọc", new HttpTimeoutException("x"))));
        assertEquals("CONNECT", GatewayErrors.kindOf(new IOException("a", new ConnectException("b"))));
        // HttpClient bọc lỗi phân giải tên trong ConnectException: phải ra DNS
        assertEquals("DNS", GatewayErrors.kindOf(new ConnectException("x") {
            {
                initCause(new UnresolvedAddressException());
            }
        }));
    }

    @Test
    void phanLoaiTheoMaHttp() {
        assertEquals("HTTP_AUTH", GatewayErrors.kindOfStatus(401));
        assertEquals("HTTP_AUTH", GatewayErrors.kindOfStatus(403));
        assertEquals("HTTP_RATE_LIMIT", GatewayErrors.kindOfStatus(429));
        assertEquals("HTTP_4XX", GatewayErrors.kindOfStatus(404));
        assertEquals("HTTP_5XX", GatewayErrors.kindOfStatus(500));
        assertEquals("HTTP_5XX", GatewayErrors.kindOfStatus(503));
    }
}
