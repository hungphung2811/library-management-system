package lms.sales.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.application.StorefrontGroup;
import lms.sales.stock.application.StorefrontQueryUseCases.ItemDetailView;
import lms.sales.stock.application.StorefrontQueryUseCases.ItemView;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-0026 mục 3.6: storefront gộp biến thể theo tác phẩm (group=WORK), chi tiết có variants, snapshot work_id. */
class StorefrontWorkGroupingTest {

    private final Harness h = new Harness();

    /** Ấn bản có workId/partNumber ở catalog; đưa vào bán kèm tồn. */
    private UUID edition(String name, long cover, long stock, UUID workId, Integer part, String language) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogTitle(id, name, "Tác giả", "ISBN-" + name, BigDecimal.valueOf(cover), null,
                false, null, 2020, null, language, null, "PAPERBACK", null, null, null, null, List.of(), workId,
                "Tác phẩm", part, part == null ? null : "Tập " + part));
        h.registerItem.execute(h.tenant, h.user, id, null, null);
        if (stock > 0) {
            h.adjust.execute(h.tenant, h.user, id, stock, "tồn đầu kỳ");
        }
        return id;
    }

    private List<ItemView> list(String keyword, String sort, Boolean inStock, String group) {
        return h.storefront.list(h.tenant, keyword, sort, inStock, group, 0, 50).items();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "work", "Work", "WORK ", "EDITIONS", "WORK;drop"})
    void groupLaNemInvalidRequestTruocKhiChamDb(String value) {
        h.book("A", 1_000, null, 1);
        h.catalogCalls = 0;
        h.storefrontListCalls = 0;
        assertThatThrownBy(() -> h.storefront.list(h.tenant, null, null, null, value, 0, 20))
                .isInstanceOfSatisfying(SalesException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(h.storefrontListCalls).isZero();
        assertThat(h.catalogCalls).isZero();
        assertThat(StorefrontGroup.parse(null)).isEqualTo(StorefrontGroup.EDITION);
    }

    @Test
    void macDinhEditionGiuHopDongCuMoiAnBanMotTheKhongCoVariantCount() {
        UUID w = UUID.randomUUID();
        edition("Tap1-VN", 50_000, 1, w, 1, "vi");
        edition("Tap1-EN", 80_000, 1, w, 1, "en");
        List<ItemView> items = list(null, null, null, null);
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(i -> {
            assertThat(i.variantCount()).isNull();
            assertThat(i.priceFrom()).isNull();
            assertThat(i.workId()).isEqualTo(w);
        });
        assertThat(list(null, null, null, "EDITION")).hasSize(2);
    }

    @Test
    void groupWorkGopAnBanCungTacPhamThanhMotTheDaiDienConHangReNhat() {
        UUID w = UUID.randomUUID();
        UUID vnHet = edition("VN-het", 40_000, 0, w, null, "vi");
        UUID enDat = edition("EN-dat", 90_000, 2, w, null, "en");
        UUID enRe = edition("EN-re", 60_000, 5, w, null, "en");
        UUID le = h.book("Le", 30_000, null, 1);

        List<ItemView> items = list(null, null, null, "WORK");

        assertThat(items).hasSize(2);
        ItemView group = items.stream().filter(i -> i.workId() != null).findFirst().orElseThrow();
        assertThat(group.titleId()).isEqualTo(enRe);
        assertThat(group.variantCount()).isEqualTo(3);
        assertThat(group.priceFrom()).isEqualTo(40_000L);
        assertThat(group.price()).isEqualTo(60_000L);
        assertThat(group.inStock()).isTrue();
        ItemView single = items.stream().filter(i -> i.titleId().equals(le)).findFirst().orElseThrow();
        assertThat(single.variantCount()).isEqualTo(1);
        assertThat(single.workId()).isNull();
        assertThat(List.of(vnHet, enDat)).doesNotContain(group.titleId());
    }

    @Test
    void groupWorkLocConHangTheoNhomVaSapTheoPriceFrom() {
        UUID w = UUID.randomUUID();
        edition("A-het", 10_000, 0, w, null, "vi");
        edition("A-con", 70_000, 3, w, null, "en");
        UUID hetHan = h.book("B-het-hang", 5_000, null, 0);
        UUID le = h.book("C", 20_000, null, 1);

        assertThat(list(null, null, true, "WORK")).extracting(ItemView::name).containsExactlyInAnyOrder("A-con", "C");
        assertThat(list(null, "price_asc", null, "WORK")).extracting(ItemView::titleId)
                .containsExactly(hetHan, h.catalogTitles.values().stream().filter(t -> t.name().equals("A-con"))
                        .findFirst().orElseThrow().id(), le);
        assertThat(list(null, "price_asc", null, "WORK").get(1).priceFrom()).isEqualTo(10_000L);
    }

    @Test
    void groupWorkChiMotLoiGoiLoCatalogBatKeSoNhom() {
        for (int i = 0; i < 12; i++) {
            UUID w = UUID.randomUUID();
            edition("S" + i + "-a", 10_000 + i, 1, w, null, "vi");
            edition("S" + i + "-b", 20_000 + i, 1, w, null, "en");
        }
        h.catalogCalls = 0;
        h.storefrontListCalls = 0;
        List<ItemView> items = list(null, null, null, "WORK");
        assertThat(items).hasSize(12);
        assertThat(h.catalogCalls).isEqualTo(1);
        assertThat(h.storefrontListCalls).isEqualTo(1);
    }

    @Test
    void groupWorkTheoTuKhoaGopTrongBoNho() {
        UUID w = UUID.randomUUID();
        edition("Harry-VN", 90_000, 1, w, null, "vi");
        UUID re = edition("Harry-EN", 50_000, 1, w, null, "en");
        h.book("Harry-khac", 10_000, null, 1);

        List<ItemView> items = list("Harry", "price_asc", null, "WORK");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).name()).isEqualTo("Harry-khac");
        ItemView group = items.get(1);
        assertThat(group.titleId()).isEqualTo(re);
        assertThat(group.variantCount()).isEqualTo(2);
        assertThat(group.priceFrom()).isEqualTo(50_000L);
        assertThat(list("Harry", null, null, null)).hasSize(3);
    }

    @Test
    void chiTietCoVariantsSapTheoTapVaDatHangVanTheoTitleId() {
        UUID w = UUID.randomUUID();
        UUID t2 = edition("Tap2", 60_000, 1, w, 2, "vi");
        UUID t1 = edition("Tap1", 50_000, 0, w, 1, "vi");
        UUID t1en = edition("Tap1-EN", 80_000, 1, w, 1, "en");
        h.book("Khac", 10_000, null, 1);
        h.catalogCalls = 0;

        ItemDetailView d = h.storefront.get(h.tenant, t2);

        assertThat(d.titleId()).isEqualTo(t2);
        assertThat(d.workId()).isEqualTo(w);
        assertThat(d.variants()).extracting(v -> v.titleId()).containsExactly(t1en, t1, t2);
        assertThat(d.variants().get(1).inStock()).isFalse();
        assertThat(d.variants().get(0).partLabel()).isEqualTo("Tập 1");
        assertThat(d.variants().get(2).price()).isEqualTo(60_000L);
        assertThat(d.variants().get(0).language()).isEqualTo("en");
        assertThat(h.catalogCalls).isLessThanOrEqualTo(2);
    }

    @Test
    void chiTietAnBanChuaGanTacPhamCoVariantsLaChinhNo() {
        UUID id = h.book("Le", 10_000, null, 1);
        ItemDetailView d = h.storefront.get(h.tenant, id);
        assertThat(d.workId()).isNull();
        assertThat(d.variants()).extracting(v -> v.titleId()).containsExactly(id);
    }

    @Test
    void daNhomCoSnapshotWorkIdKhiDuaVaoBanVaTuSuaKhiCatalogDoiTacPham() {
        UUID w1 = UUID.randomUUID();
        UUID id = edition("X", 10_000, 1, w1, null, "vi");
        assertThat(h.workSnapshots).containsEntry(id, w1);

        UUID w2 = UUID.randomUUID();
        CatalogTitle cur = h.catalogTitles.get(id);
        h.catalogTitles.put(id, new CatalogTitle(id, cur.name(), cur.author(), cur.isbn(), cur.coverPrice(), null,
                false, null, null, null, null, null, null, null, null, null, null, List.of(), w2, "Mới", null, null));
        h.workWrites.clear();

        ItemDetailView d = h.storefront.get(h.tenant, id);

        assertThat(d.workId()).isEqualTo(w2);
        assertThat(h.workWrites).containsExactly(Map.of(id, w2));
        assertThat(h.workSnapshots).containsEntry(id, w2);
    }
}
