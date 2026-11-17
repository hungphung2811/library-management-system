package lms.api.catalog.client.http;

import lms.api.catalog.client.CatalogClient;

import lms.api.catalog.dto.AddCopyRequest;
import lms.api.catalog.dto.AuthorRefView;
import lms.api.catalog.dto.CopyDetailsView;
import lms.api.catalog.dto.CopyStatus;
import lms.api.catalog.dto.CopyView;
import lms.api.catalog.dto.CreateTitleRequest;
import lms.api.catalog.dto.TitleView;
import lms.api.catalog.dto.UpdateTitleRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API nội bộ /internal/books và /internal/book-copies của catalog-service, ánh xạ sang DTO của FE. */
public class HttpCatalogClient implements CatalogClient {

    /** DTO nội bộ của catalog-service; tách khỏi DTO cho FE để hai bên đổi độc lập. */
    record InternalTitle(UUID id, String name, String author, String isbn, BigDecimal coverPrice, long availableCopies,
                         UUID tenantId, UUID publisherId, String publisherName, UUID categoryId, String categoryName,
                         List<AuthorRefView> authors) {
    }

    record InternalCopy(UUID id, UUID titleId, String barcode, String status, UUID tenantId, UUID shelfLocationId,
                        String shelfLocationCode) {
    }

    record InternalCopyDetails(UUID id, UUID titleId, String titleName, String barcode, String status,
                               BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode) {
    }

    private final RestClient client;

    public HttpCatalogClient(RestClient client) {
        this.client = client;
    }

    private static TitleView view(InternalTitle t) {
        return new TitleView(t.id(), t.name(), t.author(), t.isbn(), t.coverPrice(), t.availableCopies(), t.tenantId(),
                t.publisherId(), t.publisherName(), t.categoryId(), t.categoryName(),
                t.authors() == null ? List.of() : t.authors());
    }

    private static CopyView view(InternalCopy c) {
        return new CopyView(c.id(), c.titleId(), c.barcode(), c.status(), c.tenantId(), c.shelfLocationId(),
                c.shelfLocationCode());
    }

    private static CopyDetailsView view(InternalCopyDetails c) {
        return new CopyDetailsView(c.id(), c.titleId(), c.titleName(), c.barcode(), c.status(), c.coverPrice(),
                c.shelfLocationId(), c.shelfLocationCode());
    }

    @Override
    public List<TitleView> searchTitles(String keyword, boolean onlyAvailable, String tenantId) {
        return searchTitles(keyword, onlyAvailable, tenantId, null, null);
    }

    @Override
    public List<TitleView> searchTitles(String keyword, boolean onlyAvailable, String tenantId, UUID categoryId,
                                        UUID publisherId) {
        List<InternalTitle> titles = client.get().uri(b -> {
            b.path("/internal/books").queryParam("available", onlyAvailable);
            if (keyword != null && !keyword.isBlank()) b.queryParam("keyword", keyword);
            if (tenantId != null && !tenantId.isBlank()) b.queryParam("tenantId", tenantId);
            if (categoryId != null) b.queryParam("categoryId", categoryId);
            if (publisherId != null) b.queryParam("publisherId", publisherId);
            return b.build();
        }).retrieve().body(new ParameterizedTypeReference<>() { });
        return titles == null ? List.of() : titles.stream().map(HttpCatalogClient::view).toList();
    }

    @Override
    public TitleView getTitle(UUID id) {
        return view(client.get().uri("/internal/books/{id}", id).retrieve().body(InternalTitle.class));
    }

    @Override
    public TitleView createTitle(CreateTitleRequest request) {
        return view(client.post().uri("/internal/books").body(request).retrieve().body(InternalTitle.class));
    }

    @Override
    public TitleView updateTitle(UUID id, UpdateTitleRequest request) {
        return view(client.put().uri("/internal/books/{id}", id).body(request).retrieve().body(InternalTitle.class));
    }

    record InternalPreview(UUID id, String name, String description, String previewText) {
    }

    private static lms.api.catalog.dto.TitlePreviewView view(InternalPreview p) {
        return new lms.api.catalog.dto.TitlePreviewView(p.id(), p.name(), p.description(), p.previewText());
    }

    @Override
    public lms.api.catalog.dto.TitlePreviewView getPreview(UUID id) {
        return view(client.get().uri("/internal/books/{id}/preview", id).retrieve().body(InternalPreview.class));
    }

    @Override
    public lms.api.catalog.dto.TitlePreviewView updatePreview(UUID id,
                                                              lms.api.catalog.dto.UpdateTitlePreviewRequest request) {
        return view(client.put().uri("/internal/books/{id}/preview", id).body(request).retrieve()
                .body(InternalPreview.class));
    }

    @Override
    public List<CopyView> listCopies(UUID titleId, String tenantId) {
        List<InternalCopy> copies = client.get().uri(b -> {
            b.path("/internal/book-copies").queryParam("titleId", titleId);
            if (tenantId != null && !tenantId.isBlank()) b.queryParam("tenantId", tenantId);
            return b.build();
        }).retrieve().body(new ParameterizedTypeReference<>() { });
        return copies == null ? List.of() : copies.stream().map(HttpCatalogClient::view).toList();
    }

    @Override
    public CopyView addCopy(AddCopyRequest request) {
        return view(client.post().uri("/internal/book-copies").body(request).retrieve().body(InternalCopy.class));
    }

    @Override
    public CopyDetailsView getCopy(UUID id) {
        return view(client.get().uri("/internal/book-copies/{id}", id).retrieve().body(InternalCopyDetails.class));
    }

    @Override
    public CopyDetailsView getCopyByBarcode(String barcode) {
        return view(client.get().uri("/internal/book-copies/by-barcode/{barcode}", barcode).retrieve()
                .body(InternalCopyDetails.class));
    }

    @Override
    public CopyView moveCopyShelfLocation(UUID id, UUID shelfLocationId) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("shelfLocationId", shelfLocationId);
        return view(client.put().uri("/internal/book-copies/{id}/shelf-location", id).body(body).retrieve()
                .body(InternalCopy.class));
    }

    @Override
    public CopyView changeCopyStatus(UUID id, CopyStatus status) {
        return view(client.put().uri("/internal/book-copies/{id}/status", id).body(Map.of("status", status.name()))
                .retrieve().body(InternalCopy.class));
    }
}
