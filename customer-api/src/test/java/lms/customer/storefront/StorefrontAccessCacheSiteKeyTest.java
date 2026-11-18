package lms.customer.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tra theo site key ở bộ đệm (Public Site Key P2, T-P3): sai định dạng không gọi identity; kết quả phủ định giữ 60 giây và
 * chỉ một lời gọi; khóa bộ đệm là SHA-256 chứ không phải site key thô; site key không xuất hiện trong log; thiếu
 * applicationId coi như không khả dụng; kết quả theo mã tenant và theo site key không lẫn nhau.
 */
class StorefrontAccessCacheSiteKeyTest {

    static final String KEY = "lms_pk_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final AtomicInteger siteKeyCalls = new AtomicInteger();
    private final AtomicInteger codeCalls = new AtomicInteger();
    private final UUID applicationId = UUID.randomUUID();
    private Optional<StorefrontAccess> forKey = Optional.empty();

    private final StorefrontAccessClient client = new StorefrontAccessClient() {
        @Override
        public Optional<StorefrontAccess> issue(String tenantCode) {
            codeCalls.incrementAndGet();
            return Optional.of(new StorefrontAccess(UUID.randomUUID(), tenantCode, "T", "jwt-code", 300));
        }

        @Override
        public Optional<StorefrontAccess> issueBySiteKey(String siteKey) {
            siteKeyCalls.incrementAndGet();
            assertEquals(KEY, siteKey);
            return forKey;
        }
    };

    private StorefrontAccessCache cache() {
        return new StorefrontAccessCache(client, 100, Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofSeconds(60), ticker);
    }

    private StorefrontAccess access(UUID app) {
        return new StorefrontAccess(UUID.randomUUID(), "shop-a", "A", "jwt-key", 300, Set.of("https://shop.a.vn"),
                Set.of("SALES"), app);
    }

    @Test
    void hopLeTraAccessMangApplicationIdVaChiGoiIdentityMotLan() {
        forKey = Optional.of(access(applicationId));
        StorefrontAccessCache cache = cache();

        StorefrontAccess first = cache.resolveBySiteKey(KEY);
        cache.resolveBySiteKey(KEY);

        assertThat(first.applicationId()).isEqualTo(applicationId);
        assertThat(first.allowsOrigin("https://shop.a.vn")).isTrue();
        assertEquals(1, siteKeyCalls.get());
    }

    @Test
    void saiDinhDangVaThieuTra404MaKhongGoiIdentity() {
        StorefrontAccessCache cache = cache();
        for (String bad : new String[]{null, "", " ", "lms_pk_short", "lms_sk_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
                "LMS_PK_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", KEY + "!", KEY + " ", "lms_pk_" + "A".repeat(57),
                "lms_pk_" + "A".repeat(31), "../../etc/passwd"}) {
            PublicApiException ex = assertThrows(PublicApiException.class, () -> cache.resolveBySiteKey(bad));
            assertEquals("SHOP_NOT_AVAILABLE", ex.code());
            assertEquals(404, ex.status().value());
        }
        assertEquals(0, siteKeyCalls.get());
    }

    @Test
    void ketQuaPhuDinhGiu60GiayRoiHoiLai() {
        StorefrontAccessCache cache = cache();

        assertThrows(PublicApiException.class, () -> cache.resolveBySiteKey(KEY));
        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        assertThrows(PublicApiException.class, () -> cache.resolveBySiteKey(KEY));
        assertEquals(1, siteKeyCalls.get());

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        forKey = Optional.of(access(applicationId));
        assertThat(cache.resolveBySiteKey(KEY).applicationId()).isEqualTo(applicationId);
        assertEquals(2, siteKeyCalls.get());
    }

    @Test
    void mucDuongHetHanTrong60GiayDeOriginDoiCoHieuLuc() {
        forKey = Optional.of(access(applicationId));
        StorefrontAccessCache cache = cache();
        cache.resolveBySiteKey(KEY);

        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        cache.resolveBySiteKey(KEY);
        assertEquals(1, siteKeyCalls.get());
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        cache.resolveBySiteKey(KEY);
        assertEquals(2, siteKeyCalls.get());
    }

    @Test
    void khoaBoDemLaSha256KhongPhaiSiteKeyTho() {
        String cacheKey = StorefrontAccessCache.siteKeyCacheKey(KEY);

        assertThat(cacheKey).startsWith("k:").hasSize(2 + 64).doesNotContain(KEY).doesNotContain("lms_pk_");
        assertThat(cacheKey).isEqualTo(StorefrontAccessCache.siteKeyCacheKey(KEY));
        assertThat(cacheKey).isNotEqualTo(StorefrontAccessCache.siteKeyCacheKey(KEY.replace("ABC", "ABD")));
        // tiền tố k: không thể trùng khóa theo mã tenant (mã chỉ có [a-z0-9-])
        assertThat(StorefrontAccessCache.normalize("k:" + "a".repeat(64))).isEmpty();
    }

    @Test
    void thieuApplicationIdCoiNhuKhongKhaDung() {
        forKey = Optional.of(access(null));

        PublicApiException ex = assertThrows(PublicApiException.class, () -> cache().resolveBySiteKey(KEY));
        assertEquals("SHOP_NOT_AVAILABLE", ex.code());
    }

    @Test
    void tryResolveBySiteKeyKhongNemKhiKhongKhaDungHoacIdentityLoi() {
        StorefrontAccessCache cache = cache();
        assertThat(cache.tryResolveBySiteKey(KEY)).isEmpty();
        assertThat(cache.tryResolveBySiteKey("sai")).isEmpty();
        assertThat(cache.tryResolveBySiteKey(null)).isEmpty();

        StorefrontAccessCache down = new StorefrontAccessCache(new StorefrontAccessClient() {
            @Override
            public Optional<StorefrontAccess> issue(String tenantCode) {
                return Optional.empty();
            }

            @Override
            public Optional<StorefrontAccess> issueBySiteKey(String siteKey) {
                throw PublicApiException.serviceUnavailable();
            }
        }, 100, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), ticker);
        assertThat(down.tryResolveBySiteKey(KEY)).isEmpty();
        assertEquals(503, assertThrows(PublicApiException.class, () -> down.resolveBySiteKey(KEY)).status().value());
    }

    @Test
    void ketQuaTheoMaTenantVaTheoSiteKeyKhongLanNhau() {
        forKey = Optional.of(access(applicationId));
        StorefrontAccessCache cache = cache();

        assertThat(cache.resolve("shop-a").applicationId()).isNull();
        assertThat(cache.resolveBySiteKey(KEY).applicationId()).isEqualTo(applicationId);
        assertThat(cache.resolve("shop-a").applicationId()).isNull();
        assertEquals(1, codeCalls.get());
        assertEquals(1, siteKeyCalls.get());
    }

    @Test
    void siteKeyKhongXuatHienTrongLog() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            StorefrontAccessCache cache = cache();
            assertThat(cache.tryResolveBySiteKey(KEY)).isEmpty();
            forKey = Optional.of(access(applicationId));
            nanos.addAndGet(Duration.ofSeconds(61).toNanos());
            cache.resolveBySiteKey(KEY);
        } finally {
            root.detachAppender(appender);
        }
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(KEY)
                || e.getFormattedMessage().contains("lms_pk_"));
    }
}
