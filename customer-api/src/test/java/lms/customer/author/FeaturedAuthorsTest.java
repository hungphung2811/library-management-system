package lms.customer.author;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.customer.author.client.AuthorCatalogClient;
import lms.customer.author.client.TopSoldClient;
import lms.customer.author.dto.AuthorDtos.AuthorView;
import lms.customer.author.dto.AuthorDtos.TopSoldItem;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;

/** Thuật toán tác giả nổi bật: chỉ tác giả có sách mới, xếp theo bán chạy, tối đa 5, điền chỗ trống, chuẩn hóa. */
class FeaturedAuthorsTest {

    private List<String> newAuthors = new ArrayList<>();
    private List<TopSoldItem> topSold = new ArrayList<>();
    private final Map<UUID, String> authorOf = new HashMap<>();
    private final List<Collection<UUID>> lookups = new ArrayList<>();
    private int topSoldCalls;
    private RuntimeException salesFailure;

    private final AuthorCatalogClient catalog = new AuthorCatalogClient() {
        @Override
        public List<String> newAuthors(String serviceToken, int days, int limit) {
            assertThat(serviceToken).isEqualTo("svc");
            assertThat(days).isEqualTo(90);
            return newAuthors;
        }

        @Override
        public Map<UUID, String> authorsByTitle(String serviceToken, Collection<UUID> titleIds) {
            lookups.add(titleIds);
            Map<UUID, String> out = new HashMap<>();
            titleIds.forEach(id -> {
                if (authorOf.containsKey(id)) {
                    out.put(id, authorOf.get(id));
                }
            });
            return out;
        }
    };
    private final TopSoldClient sales = (token, days, limit) -> {
        topSoldCalls++;
        if (salesFailure != null) {
            throw salesFailure;
        }
        assertThat(days).isEqualTo(90);
        assertThat(limit).isEqualTo(100);
        return topSold;
    };
    private final FeaturedAuthors featured = new FeaturedAuthors(catalog, sales);

    private UUID sold(String author, long qty) {
        UUID id = UUID.randomUUID();
        authorOf.put(id, author);
        topSold.add(new TopSoldItem(id, qty));
        return id;
    }

    private List<String> names() {
        return featured.featured("svc").stream().map(AuthorView::name).toList();
    }

    @Test
    void khongCoSachMoiThiRongVaKhongGoiSales() {
        assertThat(names()).isEmpty();
        assertThat(topSoldCalls).isZero();
        assertThat(lookups).isEmpty();
    }

    @Test
    void chiTacGiaCoSachMoiVaXepTheoBanChay() {
        newAuthors = List.of("An", "Binh", "Chi");
        sold("Chi", 50);
        sold("An", 10);
        sold("Binh", 30);
        sold("Tac Gia Cu", 999); // không có sách mới: không được xuất hiện
        assertThat(names()).containsExactly("Chi", "Binh", "An");
    }

    @Test
    void toiDa5VaDienChoTrongBangTacGiaChuaBanTheoThuTuMoiNhat() {
        newAuthors = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7");
        sold("A6", 5);
        sold("A3", 1);
        // bán chạy trước, sau đó qty 0 theo thứ tự mới nhất trước (A1, A2, A4)
        assertThat(names()).containsExactly("A6", "A3", "A1", "A2", "A4");
    }

    @Test
    void salesRongThiTatCaQtyBang0GiuThuTuCatalog() {
        newAuthors = List.of("X", "Y", "Z");
        assertThat(names()).containsExactly("X", "Y", "Z");
        assertThat(lookups).isEmpty();
    }

    @Test
    void hoaThuongVaKhoangTrangDuocGopVaTraTenGocTuCatalog() {
        newAuthors = List.of("Nguyễn Nhật Ánh", "Tô Hoài");
        sold("  nguyễn nhật ánh ", 3);
        sold("NGUYỄN NHẬT ÁNH", 4);
        sold("tô hoài", 6);
        // Nguyễn Nhật Ánh = 7 > Tô Hoài = 6, tên trả về là tên trong newAuthors
        assertThat(names()).containsExactly("Nguyễn Nhật Ánh", "Tô Hoài");
    }

    @Test
    void hoaThuongTrongNewAuthorsGopMotGiuTenDauTien() {
        newAuthors = List.of("Lan", "lan ", "Mai");
        assertThat(names()).containsExactly("Lan", "Mai");
    }

    @Test
    void traCuuTacGiaChiMotLoiGoiLoVaTrungIdKhongNhanDoi() {
        newAuthors = List.of("A");
        UUID id = sold("A", 1);
        topSold.add(new TopSoldItem(id, 2));
        names();
        assertThat(lookups).hasSize(1);
        assertThat(lookups.get(0)).containsExactly(id);
    }

    @Test
    void loiSalesDuocNemNguyenKhongHaCap() {
        newAuthors = List.of("A");
        salesFailure = PublicApiException.serviceUnavailable();
        assertThatThrownBy(this::names).isSameAs(salesFailure);
    }
}
