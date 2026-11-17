package lms.customer.storefront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;

class StorefrontAccessCacheTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final AtomicInteger calls = new AtomicInteger();

    private StorefrontAccess access(String code, long expiresIn) {
        return new StorefrontAccess(UUID.randomUUID(), code, "Hiệu sách " + code, "jwt-" + code, expiresIn);
    }

    private StorefrontAccessCache cache(StorefrontAccessClient client) {
        return new StorefrontAccessCache(client, 100, Duration.ofSeconds(60), Duration.ofSeconds(60), ticker);
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void chuanHoaMaVaChiGoiIdentityMotLan() {
        StorefrontAccessCache cache = cache(code -> {
            calls.incrementAndGet();
            assertEquals("shop-a", code);
            return Optional.of(access(code, 300));
        });
        assertEquals("shop-a", cache.resolve("  Shop-A ").code());
        assertEquals("shop-a", cache.resolve("shop-a").code());
        assertEquals(1, calls.get());
    }

    @Test
    void maSaiDinhDangTraKhongKhaDungMaKhongGoiIdentity() {
        StorefrontAccessCache cache = cache(code -> {
            calls.incrementAndGet();
            return Optional.empty();
        });
        for (String bad : new String[] {"a", "x".repeat(51), "co khoang", "ma_gach_duoi", "../etc", "", "A!"}) {
            PublicApiException ex = assertThrows(PublicApiException.class, () -> cache.resolve(bad));
            assertEquals("SHOP_NOT_AVAILABLE", ex.code());
            assertEquals(404, ex.status().value());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void ketQuaPhuDinhGiu60GiayRoiHoiLai() {
        StorefrontAccessCache cache = cache(code -> {
            calls.incrementAndGet();
            return Optional.empty();
        });
        assertThrows(PublicApiException.class, () -> cache.resolve("khong-co"));
        advance(Duration.ofSeconds(59));
        assertThrows(PublicApiException.class, () -> cache.resolve("khong-co"));
        assertEquals(1, calls.get());
        advance(Duration.ofSeconds(2));
        assertThrows(PublicApiException.class, () -> cache.resolve("khong-co"));
        assertEquals(2, calls.get());
    }

    @Test
    void ketQuaDuongLamMoiKhiTokenConDuoi60Giay() {
        StorefrontAccessCache cache = cache(code -> {
            calls.incrementAndGet();
            return Optional.of(access(code, 300));
        });
        cache.resolve("shop-a");
        advance(Duration.ofSeconds(239));
        cache.resolve("shop-a");
        assertEquals(1, calls.get());
        advance(Duration.ofSeconds(2));
        cache.resolve("shop-a");
        assertEquals(2, calls.get());
    }

    @Test
    void loiIdentityKhongBiDemVaLaMotMaChung503() {
        AtomicInteger n = new AtomicInteger();
        StorefrontAccessCache cache = cache(code -> {
            if (n.incrementAndGet() == 1) {
                throw PublicApiException.serviceUnavailable();
            }
            return Optional.of(access(code, 300));
        });
        PublicApiException ex = assertThrows(PublicApiException.class, () -> cache.resolve("shop-a"));
        assertEquals(503, ex.status().value());
        assertEquals("shop-a", cache.resolve("shop-a").code());
        assertEquals(2, n.get());
    }

    @Test
    void nhieuRequestTruotCungMaGopThanhMotLoiGoi() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StorefrontAccessCache cache = cache(code -> {
            calls.incrementAndGet();
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(access(code, 300));
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<StorefrontAccess>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> cache.resolve("shop-a")));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 7; i++) {
                futures.add(pool.submit(() -> cache.resolve("shop-a")));
            }
            Thread.sleep(200);
            release.countDown();
            for (Future<StorefrontAccess> f : futures) {
                assertEquals("shop-a", f.get(5, TimeUnit.SECONDS).code());
            }
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void spamMaNgauNhienKhongDayTenantHopLeRaKhoiBoDem() {
        java.util.Set<String> valid = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            valid.add("shop-" + i);
        }
        StorefrontAccessCache cache = new StorefrontAccessCache(code -> {
            calls.incrementAndGet();
            return valid.contains(code) ? Optional.of(access(code, 3600)) : Optional.empty();
        }, 50, Duration.ofSeconds(60), Duration.ofSeconds(60), ticker);
        for (String code : valid) {
            cache.resolve(code);
        }
        assertEquals(20, calls.get());
        for (int i = 0; i < 3000; i++) {
            assertThrows(PublicApiException.class, () -> cache.resolve("rac-" + java.util.UUID.randomUUID()));
        }
        assertEquals(3020, calls.get());
        // Khách thật vẫn trúng bộ đệm dương: không thêm lời gọi identity nào.
        for (String code : valid) {
            assertEquals(code, cache.resolve(code).code());
        }
        assertEquals(3020, calls.get());
    }

    @Test
    void nhieuRequestCungMaPhuDinhGopThanhMotLoiGoi() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StorefrontAccessCache cache = cache(code -> {
            calls.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> assertThrows(PublicApiException.class, () -> cache.resolve("khong-co"))));
            }
            Thread.sleep(200);
            release.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get());
    }
}
