package lms.identity.tenantdata.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import lms.identity.auth.application.TenantDataTokenIssuerPort;
import lms.identity.auth.application.TenantDataTokenIssuerPort.TenantDataClaims;
import lms.identity.tenantdata.application.ExportFailure;
import lms.identity.tenantdata.application.ExportSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi exporter của một service (ADR-0031 phụ lục A): {@code GET /internal/tenant-data/export} bằng token
 * {@code svc=tenant-data} do identity ký (TTL ngắn, mỗi lần gọi một token mới, gắn tenant cần xuất), đọc ZIP con theo
 * luồng (không nạp cả thân vào RAM) và đẩy từng CSV vào sink. Không log thân phản hồi; mọi lỗi thành mã chung
 * (SOURCE_HTTP_&lt;status&gt;, SOURCE_UNREACHABLE, SOURCE_TRUNCATED, SOURCE_INVALID), thông điệp của service nguồn không bao
 * giờ được giữ lại hay chuyển ra ngoài. Token này chỉ vào được endpoint {@code @TenantDataAccess}.
 */
public class HttpExportSource implements ExportSource {

    static final String PATH = "/internal/tenant-data/export";
    static final String META = "_meta.json";
    private static final Pattern CSV = Pattern.compile("[a-z0-9_]{1,100}\\.csv");
    private static final Pattern TABLE = Pattern.compile("[a-z0-9_]{1,100}");
    private static final int META_MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final RestClient client;
    private final TenantDataTokenIssuerPort tokens;
    private final Duration tokenTtl;

    public HttpExportSource(String name, RestClient client, TenantDataTokenIssuerPort tokens, Duration tokenTtl) {
        this.name = name;
        this.client = client;
        this.tokens = tokens;
        this.tokenTtl = tokenTtl;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void export(UUID tenantId, UUID exportId, TableSink sink) {
        String token = tokens.issueTenantData(new TenantDataClaims(exportId, tenantId, tokenTtl)).value();
        String correlationId = MDC.get("correlationId");
        try {
            client.get().uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-Correlation-Id", correlationId == null ? exportId.toString() : correlationId)
                    .accept(MediaType.parseMediaType("application/zip"))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            throw new SourceFailure("SOURCE_HTTP_" + status);
                        }
                        try (InputStream body = response.getBody()) {
                            readZip(body, sink);
                        }
                        return null;
                    });
        } catch (ExportFailure | SourceFailure f) {
            throw f;
        } catch (RestClientException e) {
            throw new SourceFailure("SOURCE_UNREACHABLE", e);
        }
    }

    private static void readZip(InputStream body, TableSink sink) {
        List<String> csvFiles = new ArrayList<>();
        boolean metaSeen = false;
        try (ZipInputStream zip = new ZipInputStream(body)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String entryName = entry.getName();
                if (entry.isDirectory()) {
                    throw new SourceFailure("SOURCE_INVALID");
                }
                if (META.equals(entryName)) {
                    byte[] meta = zip.readNBytes(META_MAX_BYTES + 1);
                    if (meta.length > META_MAX_BYTES) {
                        throw new SourceFailure("SOURCE_INVALID");
                    }
                    applyMeta(meta, csvFiles, sink);
                    metaSeen = true;
                } else if (CSV.matcher(entryName).matches() && !metaSeen) {
                    csvFiles.add(entryName);
                    try (OutputStream out = sink.openFile(entryName)) {
                        zip.transferTo(out);
                    }
                } else {
                    // tên lạ (thư mục con, .., file sau _meta.json): không chép, coi là nguồn không hợp lệ
                    throw new SourceFailure("SOURCE_INVALID");
                }
            }
        } catch (ZipException e) {
            throw new SourceFailure("SOURCE_TRUNCATED", e);
        } catch (IOException e) {
            throw new SourceFailure("SOURCE_TRUNCATED", e);
        }
        if (!metaSeen) {
            throw new SourceFailure("SOURCE_TRUNCATED");
        }
    }

    private static void applyMeta(byte[] meta, List<String> csvFiles, TableSink sink) {
        JsonNode root;
        try {
            root = JSON.readTree(meta);
        } catch (IOException e) {
            throw new SourceFailure("SOURCE_INVALID", e);
        }
        if (root == null || !root.isObject() || !root.path("tables").isArray()) {
            throw new SourceFailure("SOURCE_INVALID");
        }
        sink.schemaVersion(root.path("schemaVersion").asInt(1));
        List<String> described = new ArrayList<>();
        for (JsonNode t : root.get("tables")) {
            String tableName = t.path("name").asText("");
            String file = t.path("file").asText("");
            JsonNode rows = t.path("rows");
            if (!TABLE.matcher(tableName).matches() || !csvFiles.contains(file) || !rows.canConvertToLong()
                    || rows.asLong() < 0 || described.contains(file)) {
                throw new SourceFailure("SOURCE_INVALID");
            }
            List<String> columns = new ArrayList<>();
            for (JsonNode c : t.path("columns")) {
                columns.add(c.asText());
            }
            described.add(file);
            sink.table(tableName, file, rows.asLong(), columns);
        }
        for (JsonNode e : root.path("excluded")) {
            sink.excluded(e.path("table").asText(""), e.path("reason").asText(""));
        }
    }
}
