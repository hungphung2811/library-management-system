package lms.customer.catalog.client.http;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;
import lms.customer.web.DownstreamErrorCode;
import lms.customer.web.PublicApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * GET {@code /internal/sales/storefront/items[/{titleId}[/preview]]} của sales-service (docs/api/sales.md, pha 2).
 * Phản hồi của sales được đọc vào các record riêng của lớp này rồi ÁNH XẠ TỪNG TRƯỜNG sang DTO công khai, nên trường
 * thừa nào sales thêm sau này (tồn kho, giá nhập...) cũng không thể lọt ra ngoài.
 *
 * <p>401/403 của sales khi gọi bằng token dịch vụ là lỗi cấu hình/hạ tầng (token hết hạn, sales không nhận khóa) chứ không
 * phải lỗi của khách: đổi thành 503 chung thay vì chuyển "Cần đăng nhập" cho người đọc danh mục công khai. Riêng
 * MODULE_NOT_ENABLED/TENANT_REQUIRED giữ nguyên để PublicExceptionHandler đổi thành 404 SHOP_NOT_AVAILABLE.
 */
public class HttpCatalogClient implements CatalogClient {

    private static final Set<String> SHOP_UNAVAILABLE_CODES = Set.of("MODULE_NOT_ENABLED", "TENANT_REQUIRED");
    private static final Logger log = LoggerFactory.getLogger(HttpCatalogClient.class);

    private final RestClient client;

    public HttpCatalogClient(RestClient client) {
        this.client = client;
    }

    @Override
    public BookPageView list(String serviceToken, String keyword, int page, int size) {
        UpstreamPage body = call(() -> client.get()
                .uri(b -> {
                    b.path("/internal/sales/storefront/items").queryParam("page", page).queryParam("size", size);
                    if (keyword != null) {
                        b.queryParam("keyword", "{keyword}");
                        return b.build(keyword);
                    }
                    return b.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken).retrieve().body(UpstreamPage.class));
        List<UpstreamItem> items = body.items() == null ? List.of() : body.items();
        return new BookPageView(items.stream().map(i -> new BookSummaryView(i.titleId(), i.name(), i.author(),
                i.price(), i.inStock(), i.hasPreview())).toList(), body.page(), body.size(), body.total());
    }

    @Override
    public BookDetailView get(String serviceToken, UUID bookRef) {
        UpstreamDetail d = call(() -> client.get().uri("/internal/sales/storefront/items/{id}", bookRef)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken).retrieve().body(UpstreamDetail.class));
        return new BookDetailView(d.titleId(), d.name(), d.author(), d.price(), d.inStock(), d.hasPreview(),
                d.description());
    }

    @Override
    public BookPreviewView preview(String serviceToken, UUID bookRef) {
        UpstreamPreview p = call(() -> client.get().uri("/internal/sales/storefront/items/{id}/preview", bookRef)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken).retrieve().body(UpstreamPreview.class));
        return new BookPreviewView(p.titleId(), p.name(), p.previewText());
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) {
                throw PublicApiException.serviceUnavailable();
            }
            return body;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || (status == HttpStatus.FORBIDDEN.value()
                    && !DownstreamErrorCode.isOneOf(SHOP_UNAVAILABLE_CODES, DownstreamErrorCode.of(ex)))) {
                log.warn("sales từ chối token dịch vụ storefront, status={}", status);
                throw PublicApiException.serviceUnavailable();
            }
            throw ex;
        }
    }

    record UpstreamItem(UUID titleId, String name, String author, long price, boolean inStock, boolean hasPreview) {
    }

    record UpstreamPage(List<UpstreamItem> items, long total, int page, int size) {
    }

    record UpstreamDetail(UUID titleId, String name, String author, long price, boolean inStock, boolean hasPreview,
                          String description) {
    }

    record UpstreamPreview(UUID titleId, String name, String previewText) {
    }
}
