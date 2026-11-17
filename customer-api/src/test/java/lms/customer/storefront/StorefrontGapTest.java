package lms.customer.storefront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lms.customer.config.ServicesProperties;
import lms.customer.site.client.CachedSiteClient;
import lms.customer.site.client.SiteClient;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.client.http.HttpStorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Khoảng trống tìm thấy khi rà C0 (tester). Ca mang mã BUG-x/GAP-x là lỗi đã tái hiện và đã sửa (xem docs/api/customer-api.md). */
class StorefrontGapTest {

    // ---------- HttpStorefrontAccessClient ----------

    /**
     * BUG-7 (Trung bình): mọi 404 từ identity bị coi là "storefront không khả dụng" rồi đệm phủ định 60 giây cho mã đó.
     * Nếu IDENTITY_SERVICE_URL sai, đường dẫn đổi, hoặc proxy giữa hai bên trả 404 (code NOT_FOUND hay HTML), toàn bộ
     * storefront đồng loạt báo SHOP_NOT_AVAILABLE thay vì 503, che mất sự cố cấu hình. Chỉ nên tin 404 có code
     * SHOP_NOT_AVAILABLE.
     */
    @Test
    void notFoundKhongPhaiShopNotAvailableLaLoiHaTangKhongPhaiKhongKhaDung() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpStorefrontAccessClient client = new HttpStorefrontAccessClient(builder.build(), "bi-mat");
        for (String body : new String[] {"{\"code\":\"NOT_FOUND\",\"message\":\"Không tìm thấy tài nguyên\"}",
                "<html>404</html>", ""}) {
            server.reset();
            server.expect(requestTo("http://identity/internal/storefront/access-tokens"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(body));
            PublicApiException ex = assertThrows(PublicApiException.class, () -> client.issue("shop-a"), "body=" + body);
            assertEquals(503, ex.status().value());
        }
    }

    // ---------- StorefrontAccessCache ----------

    static final class FakeTicker implements Ticker {
        long nanos;

        @Override
        public long read() {
            return nanos;
        }
    }

    static StorefrontAccess access(long expiresIn) {
        return new StorefrontAccess(UUID.randomUUID(), "shop-a", "A", "t", expiresIn);
    }

    /**
     * BUG-8 (Thấp): expiresIn rất lớn làm Duration.toNanos() tràn long (ArithmeticException) nên mọi request tới mã đó
     * trả 500 INTERNAL_ERROR. Identity là bên tin cậy nhưng giá trị nên bị chặn (min với một trần hợp lý).
     */
    @Test
    void expiresInQuaLonKhongPhaiLoi500() {
        StorefrontAccessClient client = code -> Optional.of(access(Long.MAX_VALUE / 2));
        StorefrontAccessCache cache = new StorefrontAccessCache(client, 10, Duration.ofSeconds(60), Duration.ofSeconds(60));
        assertNotNull(cache.resolve("shop-a"));
    }

    /**
     * BUG-9 (Thấp, cấu hình): khi expiresIn <= refreshMargin (ví dụ identity đặt token-ttl PT1M, margin mặc định 60s)
     * TTL đệm = 0: không đệm gì, mỗi request công khai thành một lời gọi identity (mất tác dụng bộ đệm, nhân tải).
     * Nên dùng margin = min(margin, expiresIn / 2) hoặc cảnh báo lúc khởi động.
     */
    @Test
    void tokenSongNganHonMarginVanDuocDemMotKhoang() {
        FakeTicker ticker = new FakeTicker();
        AtomicInteger calls = new AtomicInteger();
        StorefrontAccessClient client = code -> {
            calls.incrementAndGet();
            return Optional.of(access(60));
        };
        StorefrontAccessCache cache = new StorefrontAccessCache(client, 10, Duration.ofSeconds(60), Duration.ofSeconds(60), ticker);
        cache.resolve("shop-a");
        cache.resolve("shop-a");
        cache.resolve("shop-a");
        assertEquals(1, calls.get(), "identity bị gọi " + calls.get() + " lần cho 3 request liên tiếp");
    }

    @Test
    void gioiHanKichThuocBoDemKhongPhinhRaKhiQuetMaNgauNhien() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StorefrontAccessClient client = code -> {
            calls.incrementAndGet();
            return Optional.empty();
        };
        StorefrontAccessCache cache = new StorefrontAccessCache(client, 100, Duration.ofSeconds(60), Duration.ofSeconds(60));
        for (int i = 0; i < 5000; i++) {
            assertThrows(PublicApiException.class, () -> cache.resolve("ma-" + UUID.randomUUID().toString().substring(0, 12)));
        }
        assertEquals(5000, calls.get());
        // Không có API đo kích thước/xóa bộ đệm (không có recordStats, không invalidate): xem báo cáo, thiếu sót vận hành.
    }

    // ---------- ServicesProperties / ServiceClients ----------

    /**
     * BUG-10 (Trung bình, độ bền): ServicesProperties không có mặc định/kiểm tra cho timeout. Thiếu
     * `app.services.connect-timeout` thì null làm SimpleClientHttpRequestFactory ném lỗi khởi động; đặt 0 (hoặc biến môi
     * trường rỗng/0s) nghĩa là chờ vô hạn, một service phía sau treo sẽ giữ hết 20 thread Tomcat (compose đặt
     * SERVER_TOMCAT_THREADS_MAX=20) và kéo cả trang giới thiệu/health xuống. StorefrontProperties/CmsProperties đã có
     * mặc định, riêng ServicesProperties thì không.
     */
    @Test
    void timeoutPhaiCoMacDinhHuuHanKhiThieuHoacBangKhong() {
        ServicesProperties missing = new ServicesProperties("http://i", "http://s", null, null);
        assertNotNull(missing.connectTimeout());
        assertNotNull(missing.readTimeout());
        ServicesProperties zero = new ServicesProperties("http://i", "http://s", Duration.ZERO, Duration.ZERO);
        assertTrue(!zero.connectTimeout().isZero() && !zero.connectTimeout().isNegative());
        assertTrue(!zero.readTimeout().isZero() && !zero.readTimeout().isNegative());
    }

    // ---------- CachedSiteClient ----------

    static class CountingSite implements SiteClient {
        final AtomicInteger getCalls = new AtomicInteger();

        @Override
        public List<PageSummaryView> listPages() {
            return List.of();
        }

        @Override
        public PageView getPage(String slug) {
            getCalls.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (slug.startsWith("khong")) {
                throw new PublicApiException(HttpStatus.NOT_FOUND, "CMS_PAGE_NOT_FOUND", "x");
            }
            return new PageView(slug, "t", "<p>b</p>", null);
        }

        @Override
        public void submitContactRequest(ContactSubmission submission) {
        }
    }

    @Test
    void tramRequestDongThoiCungMotTrangChiGoiSystemMotLan() throws Exception {
        CountingSite delegate = new CountingSite();
        CachedSiteClient cached = new CachedSiteClient(delegate, Duration.ofSeconds(60), 500);
        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PageView>> fs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return cached.getPage("gioi-thieu");
            }));
        }
        go.countDown();
        for (Future<PageView> f : fs) {
            assertNotNull(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(1, delegate.getCalls.get());
    }

    /**
     * Thiếu sót (Thấp): 404 của trang không có/chưa xuất bản không được đệm, nên mỗi slug ngẫu nhiên hợp lệ
     * (^[a-z0-9-]{1,80}$) là một lời gọi sang system-service; nginx chỉ giới hạn 20r/s mỗi IP. Đề xuất đệm phủ định
     * ngắn (10 đến 30 giây, có trần kích thước).
     */
    @Test
    void trangKhongTonTaiDuocDemPhuDinhNganHan() {
        CountingSite delegate = new CountingSite();
        CachedSiteClient cached = new CachedSiteClient(delegate, Duration.ofSeconds(60), 500);
        for (int i = 0; i < 5; i++) {
            assertThrows(PublicApiException.class, () -> cached.getPage("khong-co"));
        }
        assertEquals(1, delegate.getCalls.get());
    }
}
