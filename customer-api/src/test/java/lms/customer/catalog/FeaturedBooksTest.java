package lms.customer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.customer.author.client.TopSoldClient;
import lms.customer.author.dto.AuthorDtos.TopSoldItem;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;
import org.junit.jupiter.api.Test;

/** Thuật toán sản phẩm nổi bật: thứ tự bán chạy, bù sách mới còn hàng cho đủ 8, không trùng, rỗng. */
class FeaturedBooksTest {

    private final CatalogClient catalog = mock(CatalogClient.class);
    private final TopSoldClient sales = mock(TopSoldClient.class);
    private final FeaturedBooks featured = new FeaturedBooks(catalog, sales);

    private static BookSummaryView book(UUID id, boolean inStock) {
        return new BookSummaryView(id, "S-" + id, "TG", 1000, inStock, false);
    }

    private static List<UUID> ids(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(UUID.randomUUID());
        }
        return out;
    }

    private void newest(List<BookSummaryView> items) {
        when(catalog.list(eq("svc"), any(), eq("newest"), eq(true), any(), eq(0), anyInt()))
                .thenReturn(new BookPageView(items, 0, 50, items.size()));
    }

    @Test
    void giuThuTuBanChayVaChiLay8DauKhiDuHang() {
        List<UUID> top = ids(12);
        when(sales.topSold("svc", 90, 100)).thenReturn(top.stream().map(i -> new TopSoldItem(i, 5)).toList());
        when(catalog.byIds(eq("svc"), anyList()))
                .thenReturn(top.subList(0, 8).stream().map(i -> book(i, true)).toList());
        List<BookSummaryView> out = featured.featured("svc");
        assertThat(out).extracting(BookSummaryView::bookRef).containsExactlyElementsOf(top.subList(0, 8));
        verify(catalog).byIds("svc", top.subList(0, 8));
        verify(catalog, never()).list(anyString(), any(), any(), anyBoolean(), any(), anyInt(), anyInt());
    }

    @Test
    void buSachMoiChoDuVaLoaiTrungVaSachHetHang() {
        List<UUID> top = ids(3);
        when(sales.topSold("svc", 90, 100)).thenReturn(top.stream().map(i -> new TopSoldItem(i, 5)).toList());
        when(catalog.byIds(eq("svc"), anyList())).thenReturn(List.of(book(top.get(0), true),
                book(top.get(1), false), book(top.get(2), true)));
        List<UUID> fresh = ids(10);
        List<BookSummaryView> items = new ArrayList<>();
        items.add(book(top.get(0), true));
        items.add(book(fresh.get(0), false));
        fresh.stream().skip(1).forEach(i -> items.add(book(i, true)));
        newest(items);
        List<BookSummaryView> out = featured.featured("svc");
        assertThat(out).hasSize(8);
        assertThat(out).extracting(BookSummaryView::bookRef).doesNotHaveDuplicates()
                .startsWith(top.get(0), top.get(2)).doesNotContain(top.get(1), fresh.get(0));
        assertThat(out).allMatch(BookSummaryView::inStock);
        verify(catalog).list("svc", null, "newest", true, null, 0, 10);
    }

    @Test
    void chuaCoDonThiKhongGoiByIdsVaLayToanBoTuSachMoi() {
        when(sales.topSold("svc", 90, 100)).thenReturn(List.of());
        List<UUID> fresh = ids(12);
        newest(fresh.stream().map(i -> book(i, true)).toList());
        List<BookSummaryView> out = featured.featured("svc");
        assertThat(out).extracting(BookSummaryView::bookRef).containsExactlyElementsOf(fresh.subList(0, 8));
        verify(catalog, never()).byIds(anyString(), anyList());
        verify(catalog).list("svc", null, "newest", true, null, 0, 8);
    }

    @Test
    void khoRong() {
        when(sales.topSold("svc", 90, 100)).thenReturn(List.of());
        newest(List.of());
        assertThat(featured.featured("svc")).isEmpty();
    }
}
