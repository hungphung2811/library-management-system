package lms.api.tenantdata.client.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lms.api.tenantdata.client.TenantDataClient;
import lms.api.tenantdata.dto.TenantDataDtos.ExportRequest;
import lms.api.tenantdata.dto.TenantDataDtos.ExportView;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Gọi xuất dữ liệu tenant của identity-service. Token, tenant và correlation id do RestClient chuyển tiếp. Tải xuống dùng
 * {@code exchange} và đưa luồng thân phản hồi thẳng cho người gọi (không {@code retrieve().body(byte[])}): bộ nhớ cố
 * định bất kể kích thước gói (tối đa 2 GB). Giới hạn: read-timeout của RestClient áp cho từng lần đọc socket (không phải cả
 * lần tải), nên gói lớn vẫn tải được miễn identity cấp dữ liệu đều. Không log thân phản hồi.
 */
public class HttpTenantDataClient implements TenantDataClient {

    private static final String BASE = "/internal/tenant-data/exports";
    private static final ParameterizedTypeReference<List<ExportView>> VIEWS = new ParameterizedTypeReference<>() { };

    private final RestClient client;

    public HttpTenantDataClient(RestClient client) {
        this.client = client;
    }

    @Override
    public ExportView request(ExportRequest request) {
        return client.post().uri(BASE).contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().body(ExportView.class);
    }

    @Override
    public List<ExportView> list() {
        return client.get().uri(BASE).retrieve().body(VIEWS);
    }

    @Override
    public ExportView get(UUID id) {
        return client.get().uri(BASE + "/{id}", id).retrieve().body(ExportView.class);
    }

    @Override
    public void download(UUID id, DownloadHandler handler) {
        stream(BASE + "/" + id + "/download", handler);
    }

    @Override
    public void downloadForTenant(UUID tenantId, UUID id, DownloadHandler handler) {
        stream("/api/platform/tenants/" + tenantId + "/data-exports/" + id + "/download", handler);
    }

    private void stream(String path, DownloadHandler handler) {
        client.get().uri(path).accept(MediaType.ALL).exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.isError()) {
                // 4xx: thân lỗi JSON nhỏ, để ApiExceptionHandler chuyển nguyên; 5xx thành 502 (không lộ chi tiết)
                byte[] payload = response.getBody().readNBytes(64 * 1024);
                if (status.is4xxClientError()) {
                    throw HttpClientErrorException.create(status, response.getStatusText(), response.getHeaders(),
                            payload, StandardCharsets.UTF_8);
                }
                throw HttpServerErrorException.create(status, response.getStatusText(), response.getHeaders(), payload,
                        StandardCharsets.UTF_8);
            }
            HttpHeaders h = response.getHeaders();
            MediaType type = h.getContentType();
            long length = h.getContentLength();
            try {
                handler.handle(new Download(type == null ? null : type.toString(), length >= 0 ? length : null,
                        h.getFirst(HttpHeaders.CONTENT_DISPOSITION), response.getBody()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });
    }
}
