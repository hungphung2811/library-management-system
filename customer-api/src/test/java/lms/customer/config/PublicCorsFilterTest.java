package lms.customer.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Quyết định CORS của /public/** (ADR-0013 2.2, 2.6, 2.7): origin nền tảng, dev, của tenant; chế độ OFF/REPORT/ENFORCE;
 * chuẩn hóa so khớp; không lộ tenant; không bao giờ Allow-Credentials; TTL 60 giây của danh sách origin.
 */
class PublicCorsFilterTest {

    static final String PLATFORM = "https://saas.example.vn";
    static final String DEV = "http://localhost:5173";
    static final String SHOP_A = "https://shop-a.test";

    private final AtomicInteger identityCalls = new AtomicInteger();
    private final AtomicReference<Set<String>> originsOfA = new AtomicReference<>(Set.of(SHOP_A));
    private final AtomicReference<Boolean> identityDown = new AtomicReference<>(false);
    private final FakeTicker ticker = new FakeTicker();

    /** Identity giả: chỉ có shop-a (origin SHOP_A) và shop-b (origin riêng). Mã khác không khả dụng. */
    private final StorefrontAccessCache cache = new StorefrontAccessCache(code -> {
        identityCalls.incrementAndGet();
        if (identityDown.get()) {
            throw PublicApiException.serviceUnavailable();
        }
        return switch (code) {
            case "shop-a" -> Optional.of(new StorefrontAccess(UUID.randomUUID(), "shop-a", "A", "t", 300, originsOfA.get()));
            case "shop-b" -> Optional.of(new StorefrontAccess(UUID.randomUUID(), "shop-b", "B", "t", 300,
                    Set.of("https://shop-b.test")));
            default -> Optional.empty();
        };
    }, 100, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), ticker);

    static final class FakeTicker implements Ticker {
        long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advance(Duration d) {
            nanos += d.toNanos();
        }
    }

    private PublicCorsFilter filter(PublicCorsMode mode) {
        return new PublicCorsFilter(new PublicCorsProperties(mode, List.of(PLATFORM), List.of(DEV), Duration.ofSeconds(600)),
                cache);
    }

    private MockHttpServletRequest request(String method, String uri, String... origins) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        for (String origin : origins) {
            r.addHeader("Origin", origin);
        }
        if ("OPTIONS".equals(method)) {
            r.addHeader("Access-Control-Request-Method", "POST");
        }
        return r;
    }

    private MockHttpServletResponse run(PublicCorsFilter f, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        return res;
    }

    private static String allowOrigin(MockHttpServletResponse res) {
        return res.getHeader("Access-Control-Allow-Origin");
    }

    @BeforeEach
    void reset() {
        identityCalls.set(0);
    }

    // ---------- Preflight ----------

    @Test
    void preflightOriginNenTangDuocPhepVoiMoiDuongDanKeCaKhongCoMa() throws Exception {
        for (String uri : List.of("/public/site/pages", "/public/auth/refresh", "/public/t/khong-co/orders",
                "/public/t/shop-a/books")) {
            MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("OPTIONS", uri, PLATFORM));
            assertThat(res.getStatus()).as(uri).isEqualTo(204);
            assertThat(allowOrigin(res)).as(uri).isEqualTo(PLATFORM);
            assertThat(res.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET, POST, OPTIONS");
            assertThat(res.getHeader("Access-Control-Allow-Headers")).contains("Authorization", "Content-Type");
            assertThat(res.getHeader("Access-Control-Max-Age")).isEqualTo("600");
            assertThat(res.getHeaders("Vary")).contains("Origin");
            assertThat(res.getHeader("Access-Control-Allow-Credentials")).isNull();
        }
        // Origin nền tảng/dev được phép không cần hỏi identity.
        assertThat(identityCalls.get()).isZero();
    }

    @Test
    void preflightOriginCuaTenantChiDuocPhepVoiMaCuaTenantDo() throws Exception {
        MockHttpServletResponse own = run(filter(PublicCorsMode.ENFORCE),
                request("OPTIONS", "/public/t/shop-a/orders", SHOP_A));
        assertThat(own.getStatus()).isEqualTo(204);
        assertThat(allowOrigin(own)).isEqualTo(SHOP_A);
        MockHttpServletResponse other = run(filter(PublicCorsMode.ENFORCE),
                request("OPTIONS", "/public/t/shop-b/orders", SHOP_A));
        assertThat(other.getStatus()).isEqualTo(204);
        assertThat(allowOrigin(other)).isNull();
    }

    @Test
    void preflightOriginLaVaMaKhongKhaDungCungMotDangKhongLoTenant() throws Exception {
        MockHttpServletResponse foreignKnownShop = run(filter(PublicCorsMode.ENFORCE),
                request("OPTIONS", "/public/t/shop-a/orders", "https://evil.test"));
        MockHttpServletResponse unknownShop = run(filter(PublicCorsMode.ENFORCE),
                request("OPTIONS", "/public/t/khong-co/orders", "https://evil.test"));
        MockHttpServletResponse ownOriginUnknownShop = run(filter(PublicCorsMode.ENFORCE),
                request("OPTIONS", "/public/t/khong-co/orders", SHOP_A));
        for (MockHttpServletResponse res : List.of(foreignKnownShop, unknownShop, ownOriginUnknownShop)) {
            assertThat(res.getStatus()).isEqualTo(204);
            assertThat(allowOrigin(res)).isNull();
            assertThat(res.getHeader("Access-Control-Allow-Methods")).isNull();
            assertThat(res.getContentAsString()).isEmpty();
        }
    }

    @Test
    void preflightKhongCoOriginVanLa204KhongHeaderCors() throws Exception {
        MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("OPTIONS", "/public/t/shop-a/orders"));
        assertThat(res.getStatus()).isEqualTo(204);
        assertThat(allowOrigin(res)).isNull();
    }

    // ---------- Chuẩn hóa so khớp ----------

    @Test
    void originNullNhieuHeaderDauPhayVaQuaDaiBiTuChoiKeCaOff() throws Exception {
        for (PublicCorsMode mode : PublicCorsMode.values()) {
            for (MockHttpServletRequest req : List.of(
                    request("GET", "/public/site/pages", "null"), request("GET", "/public/site/pages", "NULL"),
                    request("GET", "/public/site/pages", PLATFORM, PLATFORM),
                    request("GET", "/public/site/pages", PLATFORM + "," + SHOP_A),
                    request("GET", "/public/site/pages", "https://" + "a".repeat(300) + ".test"),
                    request("GET", "/public/site/pages", " "))) {
                MockHttpServletResponse res = run(filter(mode), req);
                assertThat(allowOrigin(res)).as(mode + " " + req.getHeader("Origin")).isNull();
            }
        }
    }

    @Test
    void soKhopKhongPhanBietHoaThuongNhungChinhXacSchemeHostPort() throws Exception {
        MockHttpServletResponse upper = run(filter(PublicCorsMode.ENFORCE),
                request("GET", "/public/t/shop-a/books", "HTTPS://Shop-A.TEST"));
        assertThat(allowOrigin(upper)).isEqualTo("HTTPS://Shop-A.TEST");
        for (String bad : List.of("http://shop-a.test", "https://shop-a.test:8443", "https://shop-a.test.evil.test",
                "https://evil.shop-a.test", "https://shop-a.test/", "https://shop-a.test:443")) {
            MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("GET", "/public/t/shop-a/books", bad));
            assertThat(allowOrigin(res)).as(bad).isNull();
        }
    }

    @Test
    void maCoPhanTramVaThamSoDuongDanDuocGiaiMaTruocKhiTra() throws Exception {
        for (String uri : List.of("/public/t/%73hop-a/books", "/public/t/shop-a;jsessionid=1/books", "/public/t/SHOP-A/books")) {
            MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("GET", uri, SHOP_A));
            assertThat(allowOrigin(res)).as(uri).isEqualTo(SHOP_A);
        }
        MockHttpServletResponse bad = run(filter(PublicCorsMode.ENFORCE), request("GET", "/public/t/%zz/books", SHOP_A));
        assertThat(allowOrigin(bad)).isNull();
    }

    // ---------- Chỉ domain nền tảng + dev cho đường không có mã ----------

    @Test
    void duongKhongCoMaKhongNhanOriginTenant() throws Exception {
        for (String uri : List.of("/public/site/pages", "/public/site/contact-requests", "/public/auth/refresh",
                "/public/auth/logout")) {
            assertThat(allowOrigin(run(filter(PublicCorsMode.ENFORCE), request("OPTIONS", uri, SHOP_A)))).as(uri).isNull();
            assertThat(allowOrigin(run(filter(PublicCorsMode.ENFORCE), request("OPTIONS", uri, DEV)))).as(uri).isEqualTo(DEV);
        }
        assertThat(identityCalls.get()).isZero();
    }

    // ---------- Request thường ----------

    @Test
    void requestThuongDuocPhepCoAllowOriginExposeVaVaryKhongCredentials() throws Exception {
        MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("GET", "/public/t/shop-a/books", SHOP_A));
        assertThat(allowOrigin(res)).isEqualTo(SHOP_A);
        assertThat(res.getHeader("Access-Control-Expose-Headers")).isEqualTo("X-Correlation-Id");
        assertThat(res.getHeader("Access-Control-Allow-Methods")).isNull();
        assertThat(res.getHeaders("Vary")).contains("Origin");
        assertThat(res.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void khongCoOriginThiChuyenTiepVaKhongGoiIdentity() throws Exception {
        MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("GET", "/public/t/shop-a/books"));
        assertThat(allowOrigin(res)).isNull();
        assertThat(res.getHeaders("Vary")).contains("Origin");
        assertThat(identityCalls.get()).isZero();
    }

    @Test
    void duongDanNgoaiPublicKhongBiDong() throws Exception {
        MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("GET", "/actuator/health", SHOP_A));
        assertThat(res.getHeaderNames()).doesNotContain("Vary", "Access-Control-Allow-Origin");
    }

    // ---------- Chế độ ----------

    @Test
    void offChoMoiOriginHopLeKhongGoiIdentity() throws Exception {
        MockHttpServletResponse res = run(filter(PublicCorsMode.OFF), request("OPTIONS", "/public/t/khong-co/orders",
                "https://bat-ky.test"));
        assertThat(res.getStatus()).isEqualTo(204);
        assertThat(allowOrigin(res)).isEqualTo("https://bat-ky.test");
        assertThat(identityCalls.get()).isZero();
    }

    @Test
    void reportChoPhepVaGhiLogMotLanMoiCap60Giay() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PublicCorsFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            PublicCorsFilter f = filter(PublicCorsMode.REPORT);
            for (int i = 0; i < 3; i++) {
                MockHttpServletResponse res = run(f, request("GET", "/public/t/shop-a/books", "https://moi.test"));
                assertThat(allowOrigin(res)).isEqualTo("https://moi.test");
            }
            assertThat(allowOrigin(run(f, request("GET", "/public/t/shop-a/books", SHOP_A)))).isEqualTo(SHOP_A);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("cors-would-reject", "shop-a", "https://moi.test");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---------- Hiệu lực danh sách origin ----------

    @Test
    void thuHoiOriginCoHieuLucSau60GiayKhongSomHon() throws Exception {
        PublicCorsFilter f = filter(PublicCorsMode.ENFORCE);
        assertThat(allowOrigin(run(f, request("GET", "/public/t/shop-a/books", SHOP_A)))).isEqualTo(SHOP_A);
        originsOfA.set(Set.of());
        ticker.advance(Duration.ofSeconds(59));
        assertThat(allowOrigin(run(f, request("GET", "/public/t/shop-a/books", SHOP_A)))).isEqualTo(SHOP_A);
        ticker.advance(Duration.ofSeconds(2));
        assertThat(allowOrigin(run(f, request("GET", "/public/t/shop-a/books", SHOP_A)))).isNull();
        // Thêm origin mới cũng có hiệu lực trong 60 giây.
        originsOfA.set(Set.of("https://moi.test"));
        ticker.advance(Duration.ofSeconds(61));
        assertThat(allowOrigin(run(f, request("GET", "/public/t/shop-a/books", "https://moi.test")))).isEqualTo("https://moi.test");
    }

    @Test
    void identityLoiThiKhongCorsVaKhongNemNgoaiLe() throws Exception {
        identityDown.set(true);
        MockHttpServletResponse res = run(filter(PublicCorsMode.ENFORCE), request("OPTIONS", "/public/t/shop-a/orders", SHOP_A));
        assertThat(res.getStatus()).isEqualTo(204);
        assertThat(allowOrigin(res)).isNull();
        // Origin nền tảng vẫn được phép khi identity chết.
        assertThat(allowOrigin(run(filter(PublicCorsMode.ENFORCE), request("OPTIONS", "/public/t/shop-a/orders", PLATFORM))))
                .isEqualTo(PLATFORM);
    }

    @Test
    void khongBaoGioGuiAllowCredentials() throws Exception {
        for (PublicCorsMode mode : PublicCorsMode.values()) {
            for (String method : List.of("OPTIONS", "GET", "POST")) {
                MockHttpServletResponse res = run(filter(mode), request(method, "/public/t/shop-a/books", SHOP_A));
                assertThat(res.getHeaderNames()).as(mode + " " + method).doesNotContain("Access-Control-Allow-Credentials");
            }
        }
    }
}
