package lms.api.catalog.client.http;

import lms.api.catalog.client.CatalogClient;

import lms.api.catalog.dto.AddCopyRequest;
import lms.api.catalog.dto.CopyDetailsView;
import lms.api.catalog.dto.CopyStatus;
import lms.api.catalog.dto.CopyView;
import lms.api.catalog.dto.CreateTitleRequest;
import lms.api.catalog.dto.TitleView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API nội bộ /internal/books và /internal/book-copies của catalog-service, ánh xạ sang DTO của FE. */
public class HttpCatalogClient implements CatalogClient {

    /** DTO nội bộ của catalog-service; tách khỏi DTO cho FE để hai bên đổi độc lập. */
    record InternalTitle(UUID id, String name, String author, String isbn, BigDecimal coverPrice, long availableCopies) {
    }

    record InternalCopy(UUID id, UUID titleId, String barcode, String status) {
    }

    record InternalCopyDetails(UUID id, UUID titleId, String titleName, String barcode, String status,
                               BigDecimal coverPrice) {
    }

    private final RestClient client;

    public HttpCatalogClient(RestClient client) {
        this.client = client;
    }

    private static TitleView view(InternalTitle t) {
        return new TitleView(t.id(), t.name(), t.author(), t.isbn(), t.coverPrice(), t.availableCopies());
    }

    private static CopyView view(InternalCopy c) {
        return new CopyView(c.id(), c.titleId(), c.barcode(), c.status());
    }

    private static CopyDetailsView view(InternalCopyDetails c) {
        return new CopyDetailsView(c.id(), c.titleId(), c.titleName(), c.barcode(), c.status(), c.coverPrice());
    }

    @Override
    public List<TitleView> searchTitles(String keyword, boolean onlyAvailable) {
        List<InternalTitle> titles = client.get().uri(b -> {
            b.path("/internal/books").queryParam("available", onlyAvailable);
            if (keyword != null && !keyword.isBlank()) b.queryParam("keyword", keyword);
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
    public List<CopyView> listCopies(UUID titleId) {
        List<InternalCopy> copies = client.get().uri("/internal/book-copies?titleId={id}", titleId).retrieve()
                .body(new ParameterizedTypeReference<>() { });
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
    public CopyView changeCopyStatus(UUID id, CopyStatus status) {
        return view(client.put().uri("/internal/book-copies/{id}/status", id).body(Map.of("status", status.name()))
                .retrieve().body(InternalCopy.class));
    }
}
