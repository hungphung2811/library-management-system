package lms.identity.tenantdata.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lms.identity.auth.application.TenantDataTokenIssuerPort;
import lms.identity.auth.application.TokenIssuerPort.IssuedToken;
import lms.identity.tenantdata.application.ExportSource.SourceFailure;
import lms.identity.tenantdata.application.ExportSource.TableSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client exporter (ADR-0031 phụ lục A) với một máy chủ HTTP thật cục bộ: gửi đúng đường dẫn + token svc=tenant-data cho đúng
 * tenant, đọc ZIP con theo luồng, ánh xạ lỗi thành mã chung (không lộ thân lỗi của nguồn), từ chối entry lạ.
 */
class HttpExportSourceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID EXPORT = UUID.randomUUID();

    HttpServer server;
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<TenantDataTokenIssuerPort.TenantDataClaims> claims = new AtomicReference<>();
    volatile int status = 200;
    volatile byte[] body = new byte[0];

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            path.set(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    HttpExportSource source() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory).build();
        TenantDataTokenIssuerPort issuer = c -> {
            claims.set(c);
            return new IssuedToken("tok-" + c.tenantId(), 600);
        };
        return new HttpExportSource("catalog", client, issuer, Duration.ofMinutes(10));
    }

    static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static final class Collector implements TableSink {
        final Map<String, String> files = new LinkedHashMap<>();
        final List<String> tables = new ArrayList<>();
        final List<String> excluded = new ArrayList<>();
        int schema;

        @Override
        public OutputStream openFile(String fileName) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    files.put(fileName, toString(StandardCharsets.UTF_8));
                }
            };
        }

        @Override public void table(String name, String file, long rows, List<String> columns) {
            tables.add(name + ":" + file + ":" + rows + ":" + columns);
        }

        @Override public void excluded(String table, String reason) { excluded.add(table + "=" + reason); }

        @Override public void schemaVersion(int version) { schema = version; }
    }

    static final String META = "{\"service\":\"catalog\",\"schemaVersion\":2,\"tables\":[{\"name\":\"authors\","
            + "\"file\":\"authors.csv\",\"rows\":2,\"columns\":[\"id\",\"name\"]}],\"excluded\":[{\"table\":\"x\","
            + "\"reason\":\"bí mật\"}]}";

    @Test
    void goiDungDuongDanVaTokenTenantDataChoDungTenantRoiDocZip() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("authors.csv", "﻿id,name\r\n1,A\r\n2,B\r\n");
        entries.put("_meta.json", META);
        body = zip(entries);
        Collector sink = new Collector();

        source().export(TENANT, EXPORT, sink);

        assertThat(path.get()).isEqualTo("/internal/tenant-data/export");
        assertThat(authorization.get()).isEqualTo("Bearer tok-" + TENANT);
        assertThat(claims.get().tenantId()).isEqualTo(TENANT);
        assertThat(claims.get().subject()).isEqualTo(EXPORT);
        assertThat(claims.get().ttl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(sink.files).containsEntry("authors.csv", "﻿id,name\r\n1,A\r\n2,B\r\n");
        assertThat(sink.tables).containsExactly("authors:authors.csv:2:[id, name]");
        assertThat(sink.excluded).containsExactly("x=bí mật");
        assertThat(sink.schema).isEqualTo(2);
    }

    @Test
    void maLoiHttpThanhMaChungKhongLoThanLoi() {
        status = 503;
        body = "jdbc:postgresql://internal-db password=hunter2".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> source().export(TENANT, EXPORT, new Collector()))
                .isInstanceOfSatisfying(SourceFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("SOURCE_HTTP_503");
                    assertThat(f.getMessage()).doesNotContain("hunter2");
                });
        status = 403;
        assertThatThrownBy(() -> source().export(TENANT, EXPORT, new Collector()))
                .isInstanceOfSatisfying(SourceFailure.class, f -> assertThat(f.code()).isEqualTo("SOURCE_HTTP_403"));
    }

    @Test
    void nguonKhongChayThanhSourceUnreachable() {
        server.stop(0);
        assertThatThrownBy(() -> source().export(TENANT, EXPORT, new Collector()))
                .isInstanceOfSatisfying(SourceFailure.class, f -> assertThat(f.code()).isEqualTo("SOURCE_UNREACHABLE"));
    }

    @Test
    void thieuMetaHoacZipCutThanhSourceTruncated() throws Exception {
        body = zip(Map.of("authors.csv", "id\r\n1\r\n"));
        assertThatThrownBy(() -> source().export(TENANT, EXPORT, new Collector()))
                .isInstanceOfSatisfying(SourceFailure.class, f -> assertThat(f.code()).isEqualTo("SOURCE_TRUNCATED"));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("authors.csv", "id\r\n1\r\n");
        entries.put("_meta.json", META);
        byte[] full = zip(entries);
        body = java.util.Arrays.copyOf(full, full.length / 3);
        assertThatThrownBy(() -> source().export(TENANT, EXPORT, new Collector()))
                .isInstanceOfSatisfying(SourceFailure.class, f -> assertThat(f.code()).isEqualTo("SOURCE_TRUNCATED"));
    }

    @Test
    void entryLaHoacTenChongZipSlipBiTuChoi() throws Exception {
        for (String bad : new String[] {"../evil.csv", "sub/dir.csv", "Authors.CSV", "evil.exe"}) {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put(bad, "x");
            entries.put("_meta.json", META);
            body = zip(entries);
            Collector sink = new Collector();
            assertThatThrownBy(() -> source().export(TENANT, EXPORT, sink)).as(bad)
                    .isInstanceOfSatisfying(SourceFailure.class, f -> assertThat(f.code()).isEqualTo("SOURCE_INVALID"));
            assertThat(sink.files).isEmpty();
        }
    }

    @Test
    void metaMoTaBangKhongCoFileHoacTrungFileBiTuChoi() throws Exception {
        String bad = "{\"service\":\"catalog\",\"schemaVersion\":1,\"tables\":[{\"name\":\"ghost\",\"file\":\"ghost.csv\","
                + "\"rows\":1,\"columns\":[]}],\"excluded\":[]}";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("authors.csv", "id\r\n");
        entries.put("_meta.json", bad);
        body = zip(entries);
        assertThatThrownBy(() -> source().export(TENANT, EXPORT, new Collector()))
                .isInstanceOfSatisfying(SourceFailure.class, f -> assertThat(f.code()).isEqualTo("SOURCE_INVALID"));
    }
}
