package lms.catalog.catalog.unit;

import static lms.catalog.catalog.unit.CatalogImportHarness.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.catalog.catalog.application.imports.CatalogImportSchemas;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkKind;
import lms.imports.core.ImportSchema;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportViews;
import lms.imports.core.RowStatus;
import lms.imports.core.SheetSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nhập Excel theo mô hình tác phẩm (ADR-0026 mục 3.8): loại series, mẫu titles v2 (cột cấp tác phẩm, sameWorkAsIsbn),
 * tương thích file mẫu v1, và số truy vấn không tăng theo số dòng.
 */
class CatalogImportWorkTest {

    private CatalogImportHarness h;

    @BeforeEach
    void setUp() {
        h = new CatalogImportHarness();
        h.references.seed(Reference.create(ReferenceKind.AUTHOR, "Nguyễn Nhật Ánh", null, null));
        h.references.seed(Reference.create(ReferenceKind.CATEGORY, "Văn học", null, null));
        h.references.seed(Reference.create(ReferenceKind.CATEGORY, "Thiếu nhi", null, null));
        h.references.seed(Reference.create(ReferenceKind.SERIES, "Harry Potter", null, null));
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private static ImportViews.RowView rowAt(ImportViews.RowsPage page, int rowNumber) {
        return page.items().stream().filter(r -> r.sheet().equals("DauSach") && r.rowNumber() == rowNumber).findFirst()
                .orElseThrow();
    }

    private static List<String> codes(ImportViews.RowView r) {
        return r.issues().stream().map(i -> i.code()).toList();
    }

    private byte[] titlesFile(List<Map<String, Object>> rows) {
        return CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), Map.of("DauSach", rows));
    }

    private static Map<String, Object> book(String isbn, String name, Object... more) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(row("isbn", isbn, "name", name, "author", "Tác giả", "coverPrice", 100));
        for (int i = 0; i < more.length; i += 2) m.put((String) more[i], more[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ series

    @Test
    void series_uploadVaConfirmGhiBoSachVaXoaCache() {
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.series(), List.of(
                row("name", "Dế Mèn", "description", "Phiêu lưu"),
                row("name", "dế mèn"),               // trùng trong file
                row("name", "Harry Potter"),         // đã có
                row("name", "Conan")));
        ImportViews.SessionView s = h.upload(h.series, file);
        ImportViews.RowsPage page = h.workflow.rows(h.series, h.actor, s.importId(),
                new ImportViews.RowsQuery(null, null, 0, 200));
        assertThat(page.items()).extracting(r -> r.status()).containsExactly(RowStatus.VALID, RowStatus.INVALID,
                RowStatus.DUPLICATE, RowStatus.VALID);

        ImportViews.SessionView done = h.confirmAndWait(h.series, s.importId());

        assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(done.result().created()).isEqualTo(2);
        assertThat(h.references.all(ReferenceKind.SERIES)).extracting(Reference::name)
                .containsExactlyInAnyOrder("Harry Potter", "Dế Mèn", "Conan");
        assertThat(h.evicted).contains(ReferenceKind.SERIES);
    }

    @Test
    void schema_seriesVaTitlesV2() {
        assertThat(CatalogImportSchemas.series().sheets().get(0).name()).isEqualTo("BoSach");
        assertThat(CatalogImportSchemas.series().templateVersion()).isEqualTo(1);
        ImportSchema titles = CatalogImportSchemas.titles();
        assertThat(titles.templateVersion()).isEqualTo(2);
        for (String key : List.of("sameWorkAsIsbn", "workTitle", "originalTitle", "originalLanguage", "seriesLabel",
                "partNumber", "partLabel")) {
            assertThat(titles.sheets().get(0).column(key)).as(key).isNotNull();
            assertThat(titles.sheets().get(0).column(key).required()).as(key).isFalse();
        }
    }

    // ------------------------------------------------------------------ tương thích mẫu v1

    @Test
    void mauV1ThieuCotMoiVanUploadVaMoiDongThanhTacPhamMot() {
        SheetSpec v2 = CatalogImportSchemas.titles().sheets().get(0);
        List<String> newKeys = List.of("sameWorkAsIsbn", "workTitle", "originalTitle", "originalLanguage",
                "seriesLabel", "partNumber", "partLabel");
        SheetSpec v1 = new SheetSpec("DauSach", false,
                v2.columns().stream().filter(c -> !newKeys.contains(c.key())).toList());
        byte[] file = CatalogImportHarness.xlsx(new ImportSchema("titles", 1, List.of(v1)),
                Map.of("DauSach", List.of(book("1", "A"), book("2", "B"))));

        ImportViews.SessionView s = h.upload(h.titleHandler, file);
        ImportViews.SessionView done = h.confirmAndWait(h.titleHandler, s.importId());

        assertThat(done.result().created()).isEqualTo(2);
        assertThat(h.works.byId).hasSize(2);
        assertThat(h.titles.byIsbn("1").workId()).isNotNull().isNotEqualTo(h.titles.byIsbn("2").workId());
    }

    @Test
    void mauV1CotSeriesChuaCoBoSachBiTuChoiKhongTuTao() {
        ImportViews.RowsPage page = h.rows(h.titleHandler, h.upload(h.titleHandler,
                titlesFile(List.of(book("1", "A", "series", "Bộ chưa có", "seriesNumber", 1)))));
        assertThat(rowAt(page, 2).status()).isEqualTo(RowStatus.INVALID);
        assertThat(codes(rowAt(page, 2))).contains("SERIES_NOT_FOUND");
        assertThat(h.references.all(ReferenceKind.SERIES)).hasSize(1);
    }

    // ------------------------------------------------------------------ tác phẩm mới

    @Test
    void v2MoiDongTaoTacPhamMoiMangTruongCapTacPhamVaCapAnBan() {
        ImportViews.SessionView s = h.upload(h.titleHandler, titlesFile(List.of(
                book("10", "HP1 bản Việt", "workTitle", "Harry Potter và Hòn đá phù thủy", "originalTitle",
                        "Harry Potter and the Philosopher's Stone", "originalLanguage", "en", "series", "harry potter",
                        "seriesNumber", 1, "seriesLabel", "Tập 1", "partNumber", 1, "partLabel", "Phần 1",
                        "category", "Thiếu nhi", "language", "vi"))));
        assertThat(h.rows(h.titleHandler, s).items().get(0).status()).isEqualTo(RowStatus.VALID);

        h.confirmAndWait(h.titleHandler, s.importId());

        Title t = h.titles.byIsbn("10");
        assertThat(t.workId()).isNotNull();
        assertThat(t.partNumber()).isEqualTo(1);
        assertThat(t.partLabel()).isEqualTo("Phần 1");
        assertThat(t.extras().series()).isEqualTo("Harry Potter"); // tên chuẩn của bộ sách, không phải chữ gõ
        Work w = h.works.byId.get(t.workId());
        assertThat(w.title()).isEqualTo("Harry Potter và Hòn đá phù thủy");
        assertThat(w.originalTitle()).isEqualTo("Harry Potter and the Philosopher's Stone");
        assertThat(w.originalLanguage()).isEqualTo("en");
        assertThat(w.seriesNumber()).isEqualTo(1);
        assertThat(w.seriesLabel()).isEqualTo("Tập 1");
        assertThat(w.seriesId()).isNotNull();
        assertThat(w.categoryId()).isEqualTo(t.categoryId());
        assertThat(w.kind()).isEqualTo(WorkKind.SINGLE);
    }

    @Test
    void v2CotCapTacPhamSaiBiLoiDong() {
        ImportViews.RowsPage page = h.rows(h.titleHandler, h.upload(h.titleHandler, titlesFile(List.of(
                book("1", "A", "originalLanguage", "en-US"),
                book("2", "B", "partNumber", 0),
                book("3", "C", "seriesLabel", "x".repeat(51)),
                book("4", "D", "workTitle", "w".repeat(256))))));
        for (int n = 2; n <= 5; n++) assertThat(rowAt(page, n).status()).as("dòng " + n).isEqualTo(RowStatus.INVALID);
        assertThat(codes(rowAt(page, 2))).as(String.valueOf(rowAt(page, 2).issues())).contains("INVALID_WORK");
        assertThat(codes(rowAt(page, 3))).contains("INVALID_TITLE");
    }

    // ------------------------------------------------------------------ cùng tác phẩm

    @Test
    void sameWorkAsIsbn_trongFile_ghiDichTruocVaCanhBaoTruongKhac() {
        // C trỏ B, B trỏ A, A là gốc; liệt kê ngược để chứng minh thứ tự ghi không phụ thuộc thứ tự dòng
        ImportViews.SessionView s = h.upload(h.titleHandler, titlesFile(List.of(
                book("3", "HP bìa cứng", "sameWorkAsIsbn", "2", "format", "HARDCOVER"),
                book("2", "HP ebook", "sameWorkAsIsbn", "1", "format", "EBOOK", "category", "Thiếu nhi"),
                book("1", "HP bìa mềm", "category", "Văn học", "workTitle", "Harry Potter 1"))));
        ImportViews.RowsPage page = h.rows(h.titleHandler, s);
        assertThat(rowAt(page, 2).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, 3).status()).isEqualTo(RowStatus.VALID);
        assertThat(codes(rowAt(page, 3))).containsExactly("WORK_FIELD_IGNORED"); // thể loại khác gốc
        assertThat(rowAt(page, 3).issues().get(0).severity().name()).isEqualTo("WARNING");

        ImportViews.SessionView done = h.confirmAndWait(h.titleHandler, s.importId());

        assertThat(done.result().created()).isEqualTo(3);
        assertThat(h.works.byId).hasSize(1);
        UUID workId = h.titles.byIsbn("1").workId();
        assertThat(h.titles.byIsbn("2").workId()).isEqualTo(workId);
        assertThat(h.titles.byIsbn("3").workId()).isEqualTo(workId);
        // ấn bản gắn vào tác phẩm mang thể loại của tác phẩm, không phải giá trị bị bỏ qua của dòng
        assertThat(h.titles.byIsbn("2").categoryId()).isEqualTo(h.works.byId.get(workId).categoryId());
        assertThat(h.works.byId.get(workId).title()).isEqualTo("Harry Potter 1");
    }

    @Test
    void sameWorkAsIsbn_tuAnBanCoSanTrongDb() {
        Work existing = Work.create("Tác phẩm cũ", null, null, "Tác giả", null, null, null, null, null,
                WorkKind.SINGLE, List.of());
        h.works.seed(existing);
        h.titles.seed(Title.create("Bản gốc", "Tác giả", "900", BigDecimal.TEN).withWork(existing.id()));

        ImportViews.SessionView s = h.upload(h.titleHandler, titlesFile(List.of(
                book("901", "Bản dịch", "sameWorkAsIsbn", "900", "language", "vi", "category", "Văn học"))));
        ImportViews.RowsPage page = h.rows(h.titleHandler, s);
        assertThat(rowAt(page, 2).status()).isEqualTo(RowStatus.VALID);
        assertThat(codes(rowAt(page, 2))).containsExactly("WORK_FIELD_IGNORED");

        h.confirmAndWait(h.titleHandler, s.importId());

        assertThat(h.titles.byIsbn("901").workId()).isEqualTo(existing.id());
        assertThat(h.works.byId).hasSize(1); // không tạo tác phẩm mới
        assertThat(h.titles.byIsbn("901").categoryId()).isNull(); // lấy theo tác phẩm đích, bỏ giá trị của dòng
    }

    @Test
    void sameWorkAsIsbn_cacLoiThamChieu() {
        h.titles.seed(Title.create("Chưa có tác phẩm", "X", "800", BigDecimal.TEN)); // work_id null (cửa sổ triển khai)
        ImportViews.RowsPage page = h.rows(h.titleHandler, h.upload(h.titleHandler, titlesFile(List.of(
                book("1", "không có đích", "sameWorkAsIsbn", "NOPE"),                                // 2
                book("2", "trỏ dòng hỏng", "sameWorkAsIsbn", "3"),                                  // 3
                book("3", "dòng hỏng", "coverPrice", -1),                                           // 4
                book("4", "tự trỏ", "sameWorkAsIsbn", "4"),                                         // 5
                book("5", "vòng a", "sameWorkAsIsbn", "6"),                                         // 6
                book("6", "vòng b", "sameWorkAsIsbn", "5"),                                         // 7
                book("7", "kéo theo", "sameWorkAsIsbn", "2"),                                       // 8
                book("8", "đích chưa gắn tác phẩm", "sameWorkAsIsbn", "800")))));                   // 9

        assertThat(codes(rowAt(page, 2))).contains("WORK_REF_NOT_FOUND");
        assertThat(codes(rowAt(page, 3))).contains("WORK_REF_ROW_INVALID");
        assertThat(rowAt(page, 4).status()).isEqualTo(RowStatus.INVALID);
        assertThat(codes(rowAt(page, 5))).contains("WORK_REF_CYCLE");
        assertThat(codes(rowAt(page, 6))).contains("WORK_REF_CYCLE");
        assertThat(codes(rowAt(page, 7))).contains("WORK_REF_CYCLE");
        // dòng 8 trỏ dòng 2 (tự INVALID vì đích không có): bị kéo theo
        assertThat(codes(rowAt(page, 8))).contains("WORK_REF_ROW_INVALID");
        assertThat(codes(rowAt(page, 9))).contains("WORK_REF_NOT_FOUND");
    }

    @Test
    void sameWorkAsIsbn_dongDichDaCoTrongDbThiLayTacPhamCuaDb() {
        // dòng 2 trùng ISBN với DB (DUPLICATE, bị bỏ qua) nhưng vẫn là đích hợp lệ qua ấn bản của DB
        Work existing = Work.create("Tác phẩm cũ", null, null, "Tác giả", null, null, null, null, null,
                WorkKind.SINGLE, List.of());
        h.works.seed(existing);
        h.titles.seed(Title.create("Bản gốc", "Tác giả", "900", BigDecimal.TEN).withWork(existing.id()));

        ImportViews.RowsPage page = h.rows(h.titleHandler, h.upload(h.titleHandler, titlesFile(List.of(
                book("900", "Trùng DB"),
                book("901", "Trỏ vào", "sameWorkAsIsbn", "900")))));

        assertThat(rowAt(page, 2).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(rowAt(page, 3).status()).isEqualTo(RowStatus.VALID);
    }

    // ------------------------------------------------------------------ không N+1

    @Test
    void soTruyVanKhongTangTheoSoDong() {
        assertThat(lookups(600)).isEqualTo(lookups(10));
    }

    /** Upload rồi confirm nhiều dòng cùng trỏ vào một ấn bản DB: tra ấn bản + tác phẩm đúng một lần mỗi giai đoạn. */
    private List<Integer> lookups(int rows) {
        CatalogImportHarness hh = new CatalogImportHarness();
        try {
            Work existing = Work.create("Gốc", null, null, "Tác giả", null, null, null, null, null, WorkKind.SINGLE,
                    List.of());
            hh.works.seed(existing);
            hh.titles.seed(Title.create("Gốc", "Tác giả", "ROOT", BigDecimal.TEN).withWork(existing.id()));
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < rows; i++) data.add(book("D" + i, "S" + i, "sameWorkAsIsbn", "ROOT"));
            var s = hh.upload(hh.titleHandler, CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), Map.of("DauSach", data)));
            int afterUpload = hh.works.findByIdsCalls.get();
            hh.confirmAndWait(hh.titleHandler, s.importId());
            assertThat(hh.titles.byId).hasSize(rows + 1);
            assertThat(hh.works.byId).hasSize(1);
            // số chunk phụ thuộc kích thước, nên chỉ so lần tra lúc upload và việc không tạo tác phẩm nào
            return List.of(afterUpload, hh.works.insertAllCalls.get());
        } finally {
            hh.close();
        }
    }
}
