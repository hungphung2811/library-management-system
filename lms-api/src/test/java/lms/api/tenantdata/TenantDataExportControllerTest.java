package lms.api.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lms.api.config.SecurityConfiguration;
import lms.api.tenantdata.client.TenantDataClient;
import lms.api.tenantdata.client.TenantDataClient.Download;
import lms.api.tenantdata.client.TenantDataClient.DownloadHandler;
import lms.api.tenantdata.client.http.HttpTenantDataClient;
import lms.api.tenantdata.dto.TenantDataDtos.ExportRequest;
import lms.api.tenantdata.dto.TenantDataDtos.ExportView;
import lms.api.tenantdata.dto.TenantDataDtos.PartView;
import lms.api.tenantdata.web.TenantDataExportController;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/** Hợp đồng FE của xuất dữ liệu tenant (ADR-0031 F2) ở lms-api: /api/tenant-data/exports/** và tải theo luồng. */
@WebMvcTest(TenantDataExportController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class TenantDataExportControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TenantDataClient exports;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-19T01:00:00Z");
    private static final ExportView VIEW = new ExportView(ID, "RUNNING", UUID.randomUUID(), false, null, T0, T0, null,
            null, 2, 1, 0, null, null, 0, null, List.of(new PartView("identity", "DONE", 1, 12, null, T0)));

    @Test
    void khongTokenBi401ChoMoiEndpoint() throws Exception {
        mvc.perform(get("/api/tenant-data/exports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tenant-data/exports/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tenant-data/exports/" + ID + "/download")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tenant-data/exports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/platform/tenants/" + TENANT + "/data-exports/" + ID + "/download"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(exports);
    }

    @Test
    void yeuCauTra202VaChuyenLyDoSangClient() throws Exception {
        when(exports.request(any())).thenReturn(VIEW);
        mvc.perform(post("/api/tenant-data/exports").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"chuyển hệ thống\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.parts[0].service").value("identity"));
        ArgumentCaptor<ExportRequest> body = ArgumentCaptor.forClass(ExportRequest.class);
        verify(exports).request(body.capture());
        assertThat(body.getValue().reason()).isEqualTo("chuyển hệ thống");
        // không có body vẫn hợp lệ (lý do tùy chọn)
        mvc.perform(post("/api/tenant-data/exports").with(jwt())).andExpect(status().isAccepted());
    }

    @Test
    void lyDoQuaDaiBi400KhongGoiClient() throws Exception {
        mvc.perform(post("/api/tenant-data/exports").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(exports);
    }

    @Test
    void danhSachVaChiTietChuyenNguyen() throws Exception {
        when(exports.list()).thenReturn(List.of(VIEW));
        when(exports.get(ID)).thenReturn(VIEW);
        mvc.perform(get("/api/tenant-data/exports").with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partsTotal").value(2)).andExpect(jsonPath("$[0].partsDone").value(1));
        mvc.perform(get("/api/tenant-data/exports/" + ID).with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void loi4xxCuaIdentityGiuNguyenMaVaThanChoIdorVaGioiHan() throws Exception {
        when(exports.get(ID)).thenThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.NOT_FOUND,
                "Not Found", new org.springframework.http.HttpHeaders(),
                "{\"code\":\"EXPORT_NOT_FOUND\",\"message\":\"Không tìm thấy gói xuất\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        when(exports.request(any())).thenThrow(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "x", new org.springframework.http.HttpHeaders(),
                "{\"code\":\"EXPORT_RATE_LIMITED\",\"message\":\"giới hạn\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        mvc.perform(get("/api/tenant-data/exports/" + ID).with(jwt())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
        mvc.perform(post("/api/tenant-data/exports").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EXPORT_RATE_LIMITED"));
    }

    @Test
    void taiXuongPhatLuongKemNoStoreVaTenFile() throws Exception {
        byte[] zip = "PK-fake".getBytes(StandardCharsets.UTF_8);
        doAnswer(inv -> {
            DownloadHandler h = inv.getArgument(1);
            h.handle(new Download("application/zip", (long) zip.length,
                    "attachment; filename=\"tenant-data-" + ID + ".zip\"", new ByteArrayInputStream(zip)));
            return null;
        }).when(exports).download(eq(ID), any());
        mvc.perform(get("/api/tenant-data/exports/" + ID + "/download").with(jwt())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Length", String.valueOf(zip.length)))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("tenant-data-" + ID + ".zip")))
                .andExpect(content().bytes(zip));
    }

    @Test
    void platformTaiTheoDuongDanTenantQuaClientRieng() throws Exception {
        byte[] zip = {1, 2, 3};
        doAnswer(inv -> {
            DownloadHandler h = inv.getArgument(2);
            h.handle(new Download(null, null, null, new ByteArrayInputStream(zip)));
            return null;
        }).when(exports).downloadForTenant(eq(TENANT), eq(ID), any());
        mvc.perform(get("/api/platform/tenants/" + TENANT + "/data-exports/" + ID + "/download").with(jwt()))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes(zip));
    }

    // ===== HttpTenantDataClient với máy chủ HTTP thật: luồng, không đọc hết vào bộ nhớ, ánh xạ lỗi =====

    private HttpServer server(int status, byte[] body, String contentType, AtomicReference<String> seenPath,
                              AtomicReference<String> seenAuth) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            seenPath.set(ex.getRequestURI().getPath());
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (contentType != null) {
                ex.getResponseHeaders().add("Content-Type", contentType);
            }
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"f.zip\"");
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        s.start();
        return s;
    }

    private static HttpTenantDataClient client(HttpServer s) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(5000);
        return new HttpTenantDataClient(RestClient.builder().baseUrl("http://127.0.0.1:" + s.getAddress().getPort())
                .requestFactory(f).build());
    }

    @Test
    void clientHttpDocGoiLonTheoLuongVaGoiDungDuongDan() throws Exception {
        byte[] big = new byte[3 * 1024 * 1024];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer s = server(200, big, "application/zip", path, auth);
        try {
            AtomicLong total = new AtomicLong();
            AtomicLong largestRead = new AtomicLong();
            AtomicReference<Download> seen = new AtomicReference<>();
            client(s).download(ID, d -> {
                seen.set(d);
                try (InputStream in = d.body()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total.addAndGet(n);
                        largestRead.set(Math.max(largestRead.get(), n));
                    }
                }
            });
            assertThat(path.get()).isEqualTo("/internal/tenant-data/exports/" + ID + "/download");
            assertThat(total.get()).isEqualTo(big.length);
            assertThat(largestRead.get()).isLessThanOrEqualTo(8192);
            assertThat(seen.get().contentLength()).isEqualTo((long) big.length);
            assertThat(seen.get().contentType()).isEqualTo("application/zip");
            assertThat(seen.get().contentDisposition()).contains("f.zip");

            client(s).downloadForTenant(TENANT, ID, d -> d.body().close());
            assertThat(path.get()).isEqualTo("/api/platform/tenants/" + TENANT + "/data-exports/" + ID + "/download");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void clientHttpMaLoi4xxThanhHttpClientErrorVa5xxThanhHttpServerError() throws Exception {
        List<Integer> handlerCalls = new ArrayList<>();
        HttpServer notFound = server(404, "{\"code\":\"EXPORT_NOT_FOUND\"}".getBytes(), "application/json",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> client(notFound).download(ID, d -> handlerCalls.add(1)))
                    .isInstanceOfSatisfying(HttpClientErrorException.class,
                            e -> assertThat(e.getResponseBodyAsString()).contains("EXPORT_NOT_FOUND"));
        } finally {
            notFound.stop(0);
        }
        HttpServer broken = server(503, "jdbc:postgresql://internal password=hunter2".getBytes(), "text/plain",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> client(broken).download(ID, d -> handlerCalls.add(1)))
                    .isInstanceOf(HttpServerErrorException.class);
        } finally {
            broken.stop(0);
        }
        assertThat(handlerCalls).isEmpty();
    }
}
