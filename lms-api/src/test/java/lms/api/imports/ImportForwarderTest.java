package lms.api.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import lms.api.config.ImportClientProperties;
import lms.api.config.ServiceClients;
import lms.api.config.ServicesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class ImportForwarderTest {

    private static ServiceClients clients(String base, Duration importRead) {
        ServicesProperties props = new ServicesProperties(base, base, base, base, base, base, Duration.ofSeconds(2),
                Duration.ofMillis(200));
        return new ServiceClients(props, new ImportClientProperties(importRead));
    }

    @Test
    void uploadChuyenMultipartGiuTenFileVaTraNguyenTrangThai() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/imports/titles", ex -> {
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(bos);
            body.set(bos.toString(StandardCharsets.ISO_8859_1));
            byte[] resp = "{\"id\":\"s1\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(201, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ImportForwarder fwd = new ImportForwarder(clients(base, Duration.ofSeconds(5)).createForImport(base));
            MockMultipartFile file = new MockMultipartFile("file", "dau-sach.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3, 4});

            ResponseEntity<byte[]> r = fwd.upload("/internal/imports/titles", file);

            assertEquals(201, r.getStatusCode().value());
            assertEquals("{\"id\":\"s1\"}", new String(r.getBody(), StandardCharsets.UTF_8));
            assertTrue(contentType.get().startsWith("multipart/form-data"));
            assertTrue(body.get().contains("name=\"file\""));
            assertTrue(body.get().contains("filename=\"dau-sach.xlsx\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadGiuNguyenByteVaContentDisposition() throws Exception {
        byte[] xlsx = new byte[] {'P', 'K', 3, 4, (byte) 0xFF, 0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/imports/titles/template", ex -> {
            ex.getResponseHeaders().add("Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"mau.xlsx\"");
            ex.sendResponseHeaders(200, xlsx.length);
            ex.getResponseBody().write(xlsx);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ImportForwarder fwd = new ImportForwarder(clients(base, Duration.ofSeconds(5)).createForImport(base));
            ResponseEntity<byte[]> r = fwd.download("/internal/imports/titles/template");
            assertEquals(200, r.getStatusCode().value());
            assertEquals("attachment; filename=\"mau.xlsx\"", r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
            assertTrue(java.util.Arrays.equals(xlsx, r.getBody()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loi4xxDuocNemRaDeApiExceptionHandlerChuanHoa() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/imports/x", ex -> {
            byte[] resp = "{\"code\":\"IMPORT_FILE_UNSAFE\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(422, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ImportForwarder fwd = new ImportForwarder(clients(base, Duration.ofSeconds(5)).createForImport(base));
            RestClientResponseException ex = assertThrows(RestClientResponseException.class,
                    () -> fwd.post("/internal/imports/x", "{}".getBytes(StandardCharsets.UTF_8)));
            assertEquals(422, ex.getStatusCode().value());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientImportCoTimeoutRiengDaiHonClientThuong() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ServiceClients sc = clients(base, Duration.ofSeconds(5));
            assertThrows(ResourceAccessException.class, () -> sc.create(base).get().uri("/slow").retrieve().toBodilessEntity());
            assertEquals(200, sc.createForImport(base).get().uri("/slow").retrieve().toBodilessEntity()
                    .getStatusCode().value());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void thoiGianChoImportMacDinh30Giay() {
        assertEquals(Duration.ofSeconds(30), new ImportClientProperties(null).readTimeout());
    }
}
