package lms.customer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Bulkhead, retry 1 lần cho GET, metric của lời gọi sang service phía sau, chạy trên HTTP server thật trong JVM. */
class DownstreamCallInterceptorTest {

    HttpServer server;
    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger failFirst = new AtomicInteger();
    volatile CountDownLatch hold;
    SimpleMeterRegistry registry;

    @BeforeEach
    void start() throws IOException {
        registry = new SimpleMeterRegistry();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            CountDownLatch latch = hold;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = failFirst.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 503 : 200;
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    RestClient client(int maxConcurrent) {
        ServicesProperties props = new ServicesProperties("http://x", "http://y", Duration.ofSeconds(2), Duration.ofSeconds(5));
        ServiceClients clients = new ServiceClients(props, new ResilienceProperties(maxConcurrent), registry);
        return clients.create(ServiceClients.IDENTITY, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void getBi503DuocRetryDungMotLan() {
        failFirst.set(1);
        String body = client(8).get().uri("/x").retrieve().body(String.class);
        assertEquals("{}", body);
        assertEquals(2, hits.get());
        assertEquals(1.0, registry.counter("customer.downstream.retries", "target", "identity").count());
    }

    @Test
    void getLoi503LienTiepChiThuLaiMotLanRoiBaoLoi() {
        failFirst.set(5);
        assertThrows(RestClientResponseException.class, () -> client(8).get().uri("/x").retrieve().body(String.class));
        assertEquals(2, hits.get());
    }

    @Test
    void postKhongBaoGioRetry() {
        failFirst.set(1);
        assertThrows(RestClientResponseException.class,
                () -> client(8).post().uri("/x").body("{}").retrieve().toBodilessEntity());
        assertEquals(1, hits.get());
        assertEquals(0.0, registry.counter("customer.downstream.retries", "target", "identity").count());
    }

    @Test
    void metricTimerCoTagTargetMethodOutcomeKhongCoDuongDanHayMaTenant() {
        client(8).get().uri("/shop-bi-mat-123").retrieve().body(String.class);
        var timer = registry.find("customer.downstream.calls").tag("target", "identity").tag("method", "GET")
                .tag("outcome", "success").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        registry.getMeters().forEach(m -> m.getId().getTags()
                .forEach(t -> assertTrue(!t.getValue().contains("shop-bi-mat"), "tag lộ đường dẫn: " + t)));
    }

    @Test
    void hetChoBulkheadTra503NgayKhongXepHang() throws Exception {
        hold = new CountDownLatch(1);
        RestClient client = client(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> client.get().uri("/a").retrieve().body(String.class));
            Future<String> b = pool.submit(() -> client.get().uri("/b").retrieve().body(String.class));
            long deadline = System.currentTimeMillis() + 5000;
            while (hits.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(2, hits.get(), "hai lời gọi đầu phải đang giữ chỗ");
            long t0 = System.nanoTime();
            PublicApiException ex = assertThrows(PublicApiException.class, () -> client.get().uri("/c").retrieve().body(String.class));
            assertEquals(503, ex.status().value());
            assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 1000, "phải từ chối nhanh");
            assertEquals(1.0, registry.counter("customer.downstream.rejected", "target", "identity").count());
            hold.countDown();
            assertEquals("{}", a.get(10, TimeUnit.SECONDS));
            assertEquals("{}", b.get(10, TimeUnit.SECONDS));
            // chỗ đã được trả: lời gọi kế tiếp chạy được
            assertEquals("{}", client.get().uri("/d").retrieve().body(String.class));
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }
}
