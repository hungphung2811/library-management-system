package lms.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** ADR-0016: lms-api chuyển nguyên X-Tenant-Id của người gọi xuống service, không diễn giải. */
class TokenRelayInterceptorTest {

    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static HttpHeaders relayed(MockHttpServletRequest incoming) throws IOException {
        if (incoming != null) RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incoming));
        MockClientHttpRequest outgoing = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://svc/internal/x"));
        new TokenRelayInterceptor().intercept(outgoing, new byte[0],
                (request, body) -> (ClientHttpResponse) new MockClientHttpResponse(new byte[0],
                        org.springframework.http.HttpStatus.OK));
        return outgoing.getHeaders();
    }

    @Test
    void chuyenNguyenHeaderTenantXuongService() throws IOException {
        MockHttpServletRequest incoming = new MockHttpServletRequest();
        incoming.addHeader("X-Tenant-Id", TENANT);

        assertThat(relayed(incoming).getFirst("X-Tenant-Id")).isEqualTo(TENANT);
    }

    @Test
    void khongCoHeaderThiKhongThem() throws IOException {
        assertThat(relayed(new MockHttpServletRequest()).containsKey("X-Tenant-Id")).isFalse();
    }

    @Test
    void khongCoRequestHienTaiThiKhongLoi() throws IOException {
        assertThat(relayed(null).containsKey("X-Tenant-Id")).isFalse();
    }
}
