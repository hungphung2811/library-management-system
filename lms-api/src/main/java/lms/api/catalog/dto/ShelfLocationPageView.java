package lms.api.catalog.dto;

import java.util.List;

public record ShelfLocationPageView(List<ShelfLocationView> items, long total, int page, int size) {
}
