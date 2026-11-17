package lms.api.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lms.api.reports.client.http.HttpReportClient;
import lms.api.reports.dto.OrdersReportQuery;
import lms.api.reports.dto.ReportHeaders;
import lms.api.support.TokenRelayInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Client báo cáo chuyển luồng thật sự (không đệm cả file), giữ header, ném lỗi 4xx/5xx trước khi sink chạy. */
class HttpReportClientTest {

    private HttpServer server;
    private HttpReportClient client;
    private final List<String> requests = new ArrayList<>();
    private final List<String> tenantHeaders = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(5000);
        RestClient rest = RestClient.builder().baseUrl(base).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
        client = new HttpReportClient(rest, rest);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void respond(String path, int status, String contentType, String disposition, String truncated, byte[] body) {
        server.createContext(path, ex -> {
            requests.add(ex.getRequestURI().toString());
            tenantHeaders.add(ex.getRequestHeaders().getFirst("X-Tenant-Id"));
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getResponseHeaders().set("Content-Type", contentType);
            if (disposition != null) ex.getResponseHeaders().set("Content-Disposition", disposition);
            if (truncated != null) ex.getResponseHeaders().set("X-Report-Truncated", truncated);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @Test
    void chuyenByteVaBaHeaderGoiDungDuongDanVaQuery() throws Exception {
        byte[] body = "﻿orderNo,total\r\nDEMO-1,100\r\n".getBytes(StandardCharsets.UTF_8);
        respond("/internal/sales/reporting/orders.csv", 200, "text/csv; charset=UTF-8",
                "attachment; filename=\"orders-20261117.csv\"", "true", body);

        AtomicReference<ReportHeaders> headers = new AtomicReference<>();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        client.orders(new OrdersReportQuery("2026-11-01", "2026-11-17", "POS", null), (h, in) -> {
            headers.set(h);
            in.transferTo(received);
        });

        assertEquals("text/csv; charset=UTF-8", headers.get().contentType());
        assertEquals("attachment; filename=\"orders-20261117.csv\"", headers.get().contentDisposition());
        assertEquals("true", headers.get().truncated());
        assertEquals(new String(body, StandardCharsets.UTF_8), received.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("/internal/sales/reporting/orders.csv?from=2026-11-01&to=2026-11-17&channel=POS"), requests);
    }

    @Test
    void stockDungDuongDan() throws Exception {
        respond("/internal/sales/reporting/stock.csv", 200, "text/csv", null, null, "a".getBytes());
        client.stock("true", (h, in) -> in.readAllBytes());
        assertEquals(List.of("/internal/sales/reporting/stock.csv?lowStockOnly=true"), requests);
    }

    @Test
    void quaHanDungDuongDanCirculation() throws Exception {
        respond("/internal/reporting/lending/overdue-borrowings.csv", 200, "text/csv", null, null, "a".getBytes());
        boolean[] called = {false};
        client.overdueBorrowings((h, in) -> {
            called[0] = true;
            assertNullHeader(h);
            in.readAllBytes();
        });
        assertTrue(called[0]);
        assertEquals(List.of("/internal/reporting/lending/overdue-borrowings.csv"), requests);
    }

    private static void assertNullHeader(ReportHeaders h) {
        assertEquals(null, h.truncated());
        assertEquals(null, h.contentDisposition());
    }

    @Test
    void chuyenTokenVaXTenantIdTrenLuongRequest() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token-pa").header("alg", "none").subject("pa").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "22222222-2222-2222-2222-222222222222");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        respond("/internal/reporting/lending/overdue-borrowings.csv", 200, "text/csv", null, null, "a".getBytes());

        client.overdueBorrowings((h, in) -> in.readAllBytes());

        assertEquals(List.of("Bearer token-pa"), authHeaders);
        assertEquals(List.of("22222222-2222-2222-2222-222222222222"), tenantHeaders);
    }

    @Test
    void daChuyenLuongThatSuTungDoanKhongDemCaFile() throws Exception {
        CountDownLatch firstChunkReceived = new CountDownLatch(1);
        AtomicReference<Boolean> serverSawClientRead = new AtomicReference<>(false);
        server.createContext("/internal/reporting/lending/overdue-borrowings.csv", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/csv");
            ex.sendResponseHeaders(200, 0); // chunked
            OutputStream out = ex.getResponseBody();
            out.write(new byte[1024]);
            out.flush();
            // chỉ ghi phần còn lại SAU KHI client đã đọc xong đoạn đầu: nếu client đệm cả file, sẽ không bao giờ tới đây
            try {
                serverSawClientRead.set(firstChunkReceived.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.write(new byte[2048]);
            ex.close();
        });
        server.start();

        long[] total = {0};
        client.overdueBorrowings((h, in) -> {
            byte[] first = in.readNBytes(1024);
            total[0] += first.length;
            firstChunkReceived.countDown(); // đã có dữ liệu đầu trong khi server còn chưa gửi nốt
            total[0] += in.readAllBytes().length;
        });

        assertTrue(serverSawClientRead.get(), "client phải đưa đoạn đầu cho sink trước khi server gửi xong");
        assertEquals(1024 + 2048, total[0]);
    }

    @Test
    void loi4xxNemNgoaiLeKemThanVaSinkKhongDuocGoi() {
        respond("/internal/sales/reporting/orders.csv", 400, "application/json", null, null,
                "{\"code\":\"RANGE_TOO_LARGE\"}".getBytes());
        boolean[] called = {false};

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class,
                () -> client.orders(new OrdersReportQuery(null, null, null, null), (h, in) -> called[0] = true));

        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getResponseBodyAsString().contains("RANGE_TOO_LARGE"));
        assertFalse(called[0]);
    }

    @Test
    void loi5xxNemNgoaiLeServerError() {
        respond("/internal/sales/reporting/stock.csv", 500, "application/json", null, null, "{}".getBytes());

        assertThrows(HttpServerErrorException.class, () -> client.stock(null, (h, in) -> { }));
    }
}
