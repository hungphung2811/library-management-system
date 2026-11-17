package lms.catalog.catalog.application;

import java.util.List;

public record ShelfLocationPageResponse(List<ShelfLocationResponse> items, long total, int page, int size) {

    public static ShelfLocationPageResponse from(ReferencePageResponse p) {
        return new ShelfLocationPageResponse(p.items().stream().map(ShelfLocationResponse::from).toList(), p.total(),
                p.page(), p.size());
    }
}
