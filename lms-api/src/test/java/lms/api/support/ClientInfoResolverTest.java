package lms.api.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** IP client: chỉ tin X-Real-IP từ proxy tin cậy, không bao giờ đọc X-Forwarded-For. */
class ClientInfoResolverTest {

    private static MockHttpServletRequest request(String remote, String realIp, String forwarded) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (realIp != null) r.addHeader("X-Real-IP", realIp);
        if (forwarded != null) r.addHeader("X-Forwarded-For", forwarded);
        r.addHeader("User-Agent", "Mozilla/5.0 Test");
        return r;
    }

    private static ClientInfoResolver defaults() {
        return new ClientInfoResolver(new ClientIpProperties(null));
    }

    @Test
    void tinXRealIpKhiKetNoiToiTuLoopback() {
        assertEquals("203.0.113.9", defaults().resolve(request("127.0.0.1", "203.0.113.9", null)).ip());
        assertEquals("203.0.113.9", defaults().resolve(request("0:0:0:0:0:0:0:1", "203.0.113.9", null)).ip());
    }

    @Test
    void khongTinXRealIpKhiKetNoiTuNguoiLa() {
        ClientInfo info = defaults().resolve(request("198.51.100.7", "1.2.3.4", null));
        assertEquals("198.51.100.7", info.ip());
    }

    @Test
    void boQuaXForwardedForKeCaKhiProxyTinCay() {
        assertEquals("127.0.0.1", defaults().resolve(request("127.0.0.1", null, "9.9.9.9")).ip());
        assertEquals("203.0.113.9", defaults().resolve(request("127.0.0.1", "203.0.113.9", "9.9.9.9, 8.8.8.8")).ip());
        assertEquals("198.51.100.7", defaults().resolve(request("198.51.100.7", null, "9.9.9.9")).ip());
    }

    @Test
    void xRealIpSaiDinhDangHoacTenMienBiBoVaDungDiaChiKetNoi() {
        assertEquals("127.0.0.1", defaults().resolve(request("127.0.0.1", "khong-phai-ip", null)).ip());
        assertEquals("127.0.0.1", defaults().resolve(request("127.0.0.1", "evil.example.com", null)).ip());
        assertEquals("127.0.0.1", defaults().resolve(request("127.0.0.1", "999.1.1.1", null)).ip());
    }

    @Test
    void cauHinhCidrTinDaiMang() {
        ClientInfoResolver resolver = new ClientInfoResolver(new ClientIpProperties(List.of("172.16.0.0/12", "10.1.2.3")));
        assertEquals("203.0.113.9", resolver.resolve(request("172.18.0.5", "203.0.113.9", null)).ip());
        assertEquals("203.0.113.9", resolver.resolve(request("10.1.2.3", "203.0.113.9", null)).ip());
        // ngoài dải và loopback không còn nằm trong danh sách tự đặt
        assertEquals("172.32.0.1", resolver.resolve(request("172.32.0.1", "203.0.113.9", null)).ip());
        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "203.0.113.9", null)).ip());
    }

    @Test
    void cauHinhSaiBiTuChoiLucKhoiTao() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClientInfoResolver(new ClientIpProperties(List.of("khong-phai-cidr"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientInfoResolver(new ClientIpProperties(List.of("10.0.0.0/33"))));
    }

    @Test
    void userAgentCatCon512KyTuVaBoKyTuDieuKhien() {
        MockHttpServletRequest r = request("127.0.0.1", null, null);
        r.removeHeader("User-Agent");
        r.addHeader("User-Agent", "a".repeat(900));
        assertEquals(512, defaults().resolve(r).userAgent().length());

        MockHttpServletRequest none = new MockHttpServletRequest();
        none.setRemoteAddr("127.0.0.1");
        assertNull(defaults().resolve(none).userAgent());
    }

    @Test
    void khongCoRequestThiTraNone() {
        assertEquals(ClientInfo.NONE, defaults().current());
    }
}
