package lms.sales.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StorefrontQueryUseCases;
import lms.sales.stock.application.StorefrontQueryUseCases.ItemView;
import lms.sales.stock.application.StorefrontSort;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-0019 B2: sắp xếp/lọc, snapshot giá bìa (ghi, đồng bộ lười, tự sửa) và gợi ý tìm kiếm. */
class StorefrontDiscoveryUseCaseTest {

    private final Harness h = new Harness();

    private List<String> names(List<ItemView> items) {
        return items.stream().map(ItemView::name).toList();
    }

    private List<ItemView> list(String keyword, String sort, Boolean inStock) {
        return h.storefront.list(h.tenant, keyword, sort, inStock, 0, 50).items();
    }

    // ---- StorefrontSort ----

    @Test
    void parseChiNhanAllowlistPhanBietHoaThuong() {
        assertThat(StorefrontSort.parse(null)).isNull();
        assertThat(StorefrontSort.parse("newest")).isEqualTo(StorefrontSort.NEWEST);
        assertThat(StorefrontSort.parse("price_asc")).isEqualTo(StorefrontSort.PRICE_ASC);
        assertThat(StorefrontSort.parse("price_desc")).isEqualTo(StorefrontSort.PRICE_DESC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "NEWEST", "Price_Asc", "price", "name_asc", "price_asc;drop table x",
            "createdAt", "newest "})
    void parseGiaTriLaNemInvalidRequest(String value) {
        assertThatThrownBy(() -> StorefrontSort.parse(value)).isInstanceOfSatisfying(SalesException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void sortLaKhongChamDbVaCatalog() {
        h.book("A", 1_000, null, 1);
        h.catalogCalls = 0;
        h.storefrontListCalls = 0;
        assertThatThrownBy(() -> h.storefront.list(h.tenant, null, "bogus", null, 0, 20))
                .isInstanceOfSatisfying(SalesException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> h.storefront.list(h.tenant, "a", "bogus", null, 0, 20))
                .isInstanceOf(SalesException.class);
        assertThat(h.storefrontListCalls).isZero();
        assertThat(h.catalogCalls).isZero();
    }

    // ---- không từ khóa ----

    @Test
    void macDinhVaNewestSapTheoNgayDuaVaoBanMoiTruoc() {
        h.book("Cu", 1_000, null, 1);
        h.book("Giua", 1_000, null, 1);
        h.book("Moi", 1_000, null, 1);
        assertThat(names(list(null, null, null))).containsExactly("Moi", "Giua", "Cu");
        assertThat(names(list(null, "newest", null))).containsExactly("Moi", "Giua", "Cu");
        assertThat(names(list("  ", null, null))).containsExactly("Moi", "Giua", "Cu");
    }

    @Test
    void sapTheoGiaDungSellingPriceHoacGiaBiaTangGiam() {
        h.book("Bia100k", 100_000, null, 1);
        h.book("Bia50k", 50_000, null, 1);
        h.book("Ban30kBia200k", 200_000, 30_000L, 1);
        assertThat(names(list(null, "price_asc", null))).containsExactly("Ban30kBia200k", "Bia50k", "Bia100k");
        assertThat(names(list(null, "price_desc", null))).containsExactly("Bia100k", "Bia50k", "Ban30kBia200k");
        assertThat(list(null, "price_asc", null).get(0).price()).isEqualTo(30_000);
    }

    @Test
    void cungGiaThuTuOnDinhGiuaCacTrang() {
        for (int i = 0; i < 7; i++) {
            h.book("S" + i, 10_000, null, 1);
        }
        List<UUID> viaPages = new java.util.ArrayList<>();
        for (int p = 0; p < 3; p++) {
            h.storefront.list(h.tenant, null, "price_asc", null, p, 3).items()
                    .forEach(v -> viaPages.add(v.titleId()));
        }
        List<UUID> whole = h.storefront.list(h.tenant, null, "price_asc", null, 0, 50).items().stream()
                .map(ItemView::titleId).toList();
        assertThat(viaPages).containsExactlyElementsOf(whole).doesNotHaveDuplicates();
    }

    @Test
    void locConHangChoCaHaiNhanhVaTotalTheoDuLieuLoc() {
        h.book("Con", 10_000, null, 3);
        h.book("Het", 20_000, null, 0);
        var page = h.storefront.list(h.tenant, null, null, true, 0, 20);
        assertThat(names(page.items())).containsExactly("Con");
        assertThat(page.total()).isEqualTo(1);
        assertThat(h.storefront.list(h.tenant, null, null, false, 0, 20).total()).isEqualTo(2);
        assertThat(h.storefront.list(h.tenant, null, null, null, 0, 20).total()).isEqualTo(2);
        assertThat(names(list("c", "price_desc", true))).containsExactly("Con");
    }

    @Test
    void sapTheoGiaLocMatHangKhongCoGiaHieuLucNgayODbNenTotalKhop() {
        h.book("CoGia", 10_000, null, 1);
        UUID noPrice = UUID.randomUUID();
        h.catalogTitles.put(noPrice, new CatalogLookupPort.CatalogTitle(noPrice, "KhongGia", "Tg", "I", null));
        h.registerItem.execute(h.tenant, h.user, noPrice, null, null);
        var page = h.storefront.list(h.tenant, null, "price_asc", null, 0, 20);
        assertThat(names(page.items())).containsExactly("CoGia");
        assertThat(page.total()).isEqualTo(1);
    }

    // ---- snapshot giá bìa ----

    @Test
    void dauVaoBanGhiSnapshotLamTronHalfUpKhiCoGiaBia() {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Le", "Tg", "I", new BigDecimal("99999.5")));
        h.registerItem.execute(h.tenant, h.user, id, null, null);
        assertThat(h.coverSnapshots).containsEntry(id, 100_000L);
    }

    @Test
    void dongBoLuoiDienSnapshotThieuTruocKhiSapTheoGiaVaChiMotLanDau() {
        UUID a = h.book("A", 300, null, 1);
        UUID b = h.book("B", 100, null, 1);
        h.coverSnapshots.clear(); // mô phỏng dữ liệu trước migration V6
        h.snapshotWrites.clear();
        assertThat(names(list(null, "price_asc", null))).containsExactly("B", "A");
        assertThat(h.coverSnapshots).containsEntry(a, 300L).containsEntry(b, 100L);
        assertThat(h.snapshotWrites.get(0)).hasSize(2);
        // lần sau: không còn gì thiếu, không gọi catalog thêm để đồng bộ
        h.catalogCalls = 0;
        list(null, "price_asc", null);
        assertThat(h.catalogCalls).isEqualTo(1); // chỉ lô tra tên/giá cho trang
    }

    @Test
    void dongBoLuoiToiDa500MoiLan() {
        for (int i = 0; i < 501; i++) {
            h.book("S" + i, 1_000 + i, null, 0);
        }
        h.coverSnapshots.clear();
        h.snapshotWrites.clear();
        h.storefront.list(h.tenant, null, "price_asc", null, 0, 10);
        assertThat(h.snapshotWrites.get(0)).hasSize(500);
    }

    @Test
    void sapTheoNewestKhongDongBoLuoi() {
        h.book("A", 300, null, 1);
        h.coverSnapshots.clear();
        h.snapshotWrites.clear();
        // heal sẽ điền snapshot của dòng hiện ra, nhưng không có đợt đồng bộ lô riêng
        list(null, "newest", null);
        assertThat(h.snapshotWrites).hasSize(1);
        assertThat(h.snapshotWrites.get(0)).hasSize(1);
    }

    @Test
    void tuSuaLechKhiGiaBiaDoiOCatalog() {
        UUID id = h.book("A", 1_000, null, 1);
        assertThat(h.coverSnapshots).containsEntry(id, 1_000L);
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "A", "Tg", "I", BigDecimal.valueOf(2_000)));
        h.snapshotWrites.clear();

        var page = h.storefront.list(h.tenant, null, null, null, 0, 20);
        assertThat(page.items().get(0).price()).isEqualTo(2_000); // giá hiển thị luôn lấy sống từ catalog
        assertThat(h.coverSnapshots).containsEntry(id, 2_000L);

        h.snapshotWrites.clear();
        h.storefront.get(h.tenant, id); // đã khớp: không ghi nữa
        assertThat(h.snapshotWrites).isEmpty();

        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "A", "Tg", "I", BigDecimal.valueOf(3_000)));
        assertThat(h.storefront.get(h.tenant, id).price()).isEqualTo(3_000);
        assertThat(h.coverSnapshots).containsEntry(id, 3_000L); // chi tiết cũng tự sửa
    }

    @Test
    void tuSuaToiDa50DongMoiLan() {
        for (int i = 0; i < 60; i++) {
            h.book("S" + i, 1_000, null, 0);
        }
        h.catalogTitles.replaceAll((id, t) -> new CatalogLookupPort.CatalogTitle(id, t.name(), t.author(), t.isbn(),
                BigDecimal.valueOf(5_000)));
        h.snapshotWrites.clear();
        h.storefront.list(h.tenant, null, null, null, 0, 50);
        assertThat(h.snapshotWrites).hasSize(1);
        assertThat(h.snapshotWrites.get(0)).hasSize(50);
    }

    @Test
    void loiGhiSnapshotKhongLamHongGetCongKhai() {
        UUID id = h.book("A", 1_000, null, 1);
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "A", "Tg", "I", BigDecimal.valueOf(2_000)));
        h.failSnapshotWrites = true;
        assertThat(h.storefront.list(h.tenant, null, null, null, 0, 20).items()).hasSize(1);
        assertThat(h.storefront.list(h.tenant, null, "price_asc", null, 0, 20).items()).hasSize(1);
        assertThat(h.storefront.list(h.tenant, "a", "price_asc", null, 0, 20).items()).hasSize(1);
        assertThat(h.storefront.get(h.tenant, id).price()).isEqualTo(2_000);
    }

    // ---- có từ khóa ----

    @Test
    void coTuKhoaKhongSortThiTheoTenCoSortThiTheoGiaSong() {
        h.book("Sach Z", 10_000, null, 1);
        h.book("Sach A", 30_000, null, 1);
        h.book("Sach M", 20_000, null, 1);
        h.book("Khac", 1, null, 1);
        assertThat(names(list("sach", null, null))).containsExactly("Sach A", "Sach M", "Sach Z");
        assertThat(names(list("sach", "price_asc", null))).containsExactly("Sach Z", "Sach M", "Sach A");
        assertThat(names(list("sach", "price_desc", null))).containsExactly("Sach A", "Sach M", "Sach Z");
        assertThat(names(list("sach", "newest", null))).containsExactly("Sach M", "Sach A", "Sach Z");
    }

    @Test
    void coTuKhoaLocConHangVaPhanTrangTrongBoNho() {
        h.book("Sach 1", 1_000, null, 0);
        h.book("Sach 2", 2_000, null, 5);
        h.book("Sach 3", 3_000, null, 5);
        var page = h.storefront.list(h.tenant, "sach", null, true, 0, 1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(names(page.items())).containsExactly("Sach 2");
        assertThat(names(h.storefront.list(h.tenant, "sach", null, true, 1, 1).items())).containsExactly("Sach 3");
    }

    // ---- gợi ý ----

    @Test
    void goiYKiemTraDoDaiVaKyTuDieuKhien() {
        for (String bad : new String[] {null, "", " ", "a", " a ", "x".repeat(51), "ab\ncd", "ab\tcd"}) {
            assertThatThrownBy(() -> h.storefront.suggest(h.tenant, bad, null)).as("q=%s", bad)
                    .isInstanceOfSatisfying(SalesException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        // 2 và 50 code point hợp lệ (cắt khoảng trắng trước khi đếm)
        h.storefront.suggest(h.tenant, " ab ", null);
        h.storefront.suggest(h.tenant, "x".repeat(50), null);
    }

    @Test
    void goiYChiSachDangBanCoGiaTienToTruocRoiTheoTenVaCatLimit() {
        h.book("Mat Biec", 1_000, null, 1);
        h.book("Biec Xanh", 1_000, null, 1);
        h.book("Biec Do", 1_000, null, 1);
        UUID off = h.book("Biec Tat", 1_000, null, 1);
        h.stockPort.saveAttributes(h.item(off).withActive(false));
        UUID noPrice = UUID.randomUUID();
        h.catalogTitles.put(noPrice, new CatalogLookupPort.CatalogTitle(noPrice, "Biec KhongGia", "Tg", "I", null));
        h.registerItem.execute(h.tenant, h.user, noPrice, null, null);
        UUID notSold = UUID.randomUUID(); // có ở catalog nhưng chưa đưa vào bán
        h.catalogTitles.put(notSold, new CatalogLookupPort.CatalogTitle(notSold, "Biec Chua Ban", "Tg", "I",
                BigDecimal.TEN));

        var s = h.storefront.suggest(h.tenant, "BIEC", null);
        assertThat(s).extracting(StorefrontQueryUseCases.SuggestionView::name)
                .containsExactly("Biec Do", "Biec Xanh", "Mat Biec"); // tiền tố trước, rồi theo tên
        assertThat(h.storefront.suggest(h.tenant, "biec", 1)).hasSize(1);
        assertThat(h.storefront.suggest(h.tenant, "biec", 0)).hasSize(3);
        assertThat(h.storefront.suggest(h.tenant, "zzz", null)).isEmpty();
    }

    @Test
    void goiYMacDinh5ToiDa8() {
        for (int i = 0; i < 12; i++) {
            h.book("Sach " + String.format("%02d", i), 1_000, null, 0);
        }
        assertThat(h.storefront.suggest(h.tenant, "sach", null)).hasSize(5);
        assertThat(h.storefront.suggest(h.tenant, "sach", -3)).hasSize(5);
        assertThat(h.storefront.suggest(h.tenant, "sach", 3)).hasSize(3);
        assertThat(h.storefront.suggest(h.tenant, "sach", 100)).hasSize(8);
    }

    @Test
    void goiYChiBaTruongKhongLoGiaHayTon() {
        assertThat(StorefrontQueryUseCases.SuggestionView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName).containsExactly("titleId", "name", "author");
    }
}
