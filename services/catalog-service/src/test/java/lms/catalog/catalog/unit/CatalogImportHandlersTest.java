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
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.Title;
import lms.catalog.inventory.domain.BookCopy;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportViews;
import lms.imports.core.RowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Handler nhập catalog (ADR-0024) chạy qua ImportWorkflow + InMemoryImportSessionStore với kho giả. */
class CatalogImportHandlersTest {

    private CatalogImportHarness h;

    @BeforeEach
    void setUp() {
        h = new CatalogImportHarness();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private static ImportViews.RowView rowAt(ImportViews.RowsPage page, String sheet, int rowNumber) {
        return page.items().stream().filter(r -> r.sheet().equals(sheet) && r.rowNumber() == rowNumber).findFirst().orElseThrow();
    }

    private static List<String> codes(ImportViews.RowView r) {
        return r.issues().stream().map(i -> i.code()).toList();
    }

    // ------------------------------------------------------------------ tham chiếu

    @Test
    void tacGia_phanLoaiHopLeTrungTrongFileTrungDbVaLoiDomain() {
        h.references.seed(Reference.create(ReferenceKind.AUTHOR, "Tô Hoài", null, null));
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.authors(), List.of(
                row("name", "Nguyễn Nhật Ánh", "birthYear", 1955, "nationality", "Việt Nam"),   // dòng 2: hợp lệ
                row("name", "  nguyễn   nhật ánh "),                                            // dòng 3: trùng trong file
                row("name", "TÔ HOÀI"),                                                         // dòng 4: trùng DB (khác hoa thường)
                row("name", "Tác giả sai năm", "birthYear", 3000),                              // dòng 5: năm sinh tương lai
                row("description", "thiếu tên"),                                                // dòng 6: thiếu tên
                row("name", "Có website sai", "website", "ftp://x")));                          // dòng 7: URL sai

        ImportViews.SessionView s = h.upload(h.authors, file);
        ImportViews.RowsPage page = h.rows(h.authors, s);

        assertThat(rowAt(page, "TacGia", 2).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, "TacGia", 3).status()).isEqualTo(RowStatus.INVALID);
        assertThat(codes(rowAt(page, "TacGia", 3))).contains("DUPLICATE_IN_FILE");
        assertThat(rowAt(page, "TacGia", 4).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(rowAt(page, "TacGia", 5).status()).isEqualTo(RowStatus.INVALID);
        assertThat(codes(rowAt(page, "TacGia", 5))).contains("INVALID_AUTHOR");
        assertThat(rowAt(page, "TacGia", 6).status()).isEqualTo(RowStatus.INVALID);
        assertThat(rowAt(page, "TacGia", 7).status()).isEqualTo(RowStatus.INVALID);
        assertThat(s.totals().valid()).isEqualTo(1);
        // validate chỉ đọc: chưa ghi gì
        assertThat(h.references.all(ReferenceKind.AUTHOR)).hasSize(1);
    }

    @Test
    void tacGia_confirmChiGhiDongHopLeVaXoaCache() {
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.authors(), List.of(
                row("name", "A1", "birthYear", 1950, "deathYear", 2000), row("name", "A2"), row("name", "A2")));
        ImportViews.SessionView s = h.upload(h.authors, file);

        ImportViews.SessionView done = h.confirmAndWait(h.authors, s.importId());

        assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(done.result().created()).isEqualTo(2);
        List<Reference> saved = h.references.all(ReferenceKind.AUTHOR);
        assertThat(saved).extracting(Reference::name).containsExactlyInAnyOrder("A1", "A2");
        Reference a1 = saved.stream().filter(r -> r.name().equals("A1")).findFirst().orElseThrow();
        assertThat(a1.extras().birthYear()).isEqualTo(1950);
        assertThat(h.evicted).contains(ReferenceKind.AUTHOR);
        assertThat(h.audits).containsExactly("authors:2/0/0");
    }

    @Test
    void confirmKiemLaiTrungVoiDbLucGhi() {
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.publishers(), List.of(row("name", "NXB Mới"), row("name", "NXB Hai")));
        ImportViews.SessionView s = h.upload(h.publishers, file);
        assertThat(s.totals().valid()).isEqualTo(2);
        // người khác tạo "NXB Mới" trong lúc chờ confirm
        h.references.seed(Reference.create(ReferenceKind.PUBLISHER, "NXB Mới", null, null));

        ImportViews.SessionView done = h.confirmAndWait(h.publishers, s.importId());

        assertThat(done.result().created()).isEqualTo(1);
        assertThat(done.result().skippedDuplicate()).isEqualTo(1);
        assertThat(h.references.all(ReferenceKind.PUBLISHER)).hasSize(2);
    }

    @Test
    void ghiDongThoi_chunkRollbackRoiChayLaiTungDongKhongMatDong() {
        h.references.raceWinner = Reference.create(ReferenceKind.PUBLISHER, "NXB A", null, null);
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.publishers(),
                List.of(row("name", "NXB A"), row("name", "NXB B"), row("name", "NXB C")));
        ImportViews.SessionView s = h.upload(h.publishers, file);

        ImportViews.SessionView done = h.confirmAndWait(h.publishers, s.importId());

        assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(done.result().created()).isEqualTo(2);
        assertThat(done.result().skippedDuplicate()).isEqualTo(1);
        assertThat(done.result().failed()).isEqualTo(0);
        assertThat(h.references.all(ReferenceKind.PUBLISHER)).extracting(Reference::name)
                .containsExactlyInAnyOrder("NXB A", "NXB B", "NXB C");
    }

    @Test
    void viTriKe_capacityVaTrungMaKhongPhanBietHoaThuong() {
        h.references.seed(Reference.create(ReferenceKind.SHELF_LOCATION, "A1-01", null, null));
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.shelfLocations(), List.of(
                row("code", "a1-01"),                       // trùng DB
                row("code", "B2-02", "capacity", 500),     // hợp lệ
                row("code", "C3-03", "capacity", 0)));      // sức chứa 0
        ImportViews.SessionView s = h.upload(h.shelves, file);
        ImportViews.RowsPage page = h.rows(h.shelves, s);

        assertThat(rowAt(page, "ViTriKe", 2).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(rowAt(page, "ViTriKe", 3).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, "ViTriKe", 4).status()).isEqualTo(RowStatus.INVALID);
        h.confirmAndWait(h.shelves, s.importId());
        assertThat(h.references.all(ReferenceKind.SHELF_LOCATION)).extracting(Reference::name).contains("B2-02");
    }

    // ------------------------------------------------------------------ thể loại: cha trước con

    @Test
    void theLoai_conGhiSauChaDuDongConDungTruocChaTrongFile() {
        Reference existingRoot = Reference.create(ReferenceKind.CATEGORY, "Khoa học", null, null);
        h.references.seed(existingRoot);
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.categories(), List.of(
                row("name", "Vật lý", "parentName", "Khoa học"),         // cha có trong DB
                row("name", "Cơ học", "parentName", "Văn học"),          // cha là dòng 4 của cùng file (đứng sau)
                row("name", "Văn học"),                                   // gốc mới
                row("name", "Thơ", "parentName", "Cơ học"),              // cha là con -> quá 2 cấp
                row("name", "Mồ côi", "parentName", "Không có"),         // cha không tồn tại
                row("name", "Tự cha", "parentName", "Tự cha")));         // vòng

        ImportViews.SessionView s = h.upload(h.categories, file);
        ImportViews.RowsPage page = h.rows(h.categories, s);

        assertThat(rowAt(page, "TheLoai", 2).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, "TheLoai", 3).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, "TheLoai", 4).status()).isEqualTo(RowStatus.VALID);
        assertThat(codes(rowAt(page, "TheLoai", 5))).contains("CATEGORY_TOO_DEEP");
        assertThat(codes(rowAt(page, "TheLoai", 6))).contains("CATEGORY_NOT_FOUND");
        assertThat(codes(rowAt(page, "TheLoai", 7))).contains("CATEGORY_CYCLE");

        ImportViews.SessionView done = h.confirmAndWait(h.categories, s.importId());

        assertThat(done.result().created()).isEqualTo(3);
        Map<String, Reference> byName = new java.util.HashMap<>();
        h.references.all(ReferenceKind.CATEGORY).forEach(r -> byName.put(r.name(), r));
        assertThat(byName.get("Vật lý").parentId()).isEqualTo(existingRoot.id());
        assertThat(byName.get("Cơ học").parentId()).isEqualTo(byName.get("Văn học").id());
        assertThat(byName.get("Văn học").parentId()).isNull();
    }

    @Test
    void theLoai_chaDaNgungSuDungHoacKhongPhaiGocBiTuChoi() {
        Reference root = Reference.create(ReferenceKind.CATEGORY, "Gốc", null, null);
        Reference child = Reference.restore(UUID.randomUUID(), ReferenceKind.CATEGORY, null, "Con", null, root.id(), true);
        Reference inactive = Reference.restore(UUID.randomUUID(), ReferenceKind.CATEGORY, null, "Cũ", null, null, false);
        h.references.seed(root);
        h.references.seed(child);
        h.references.seed(inactive);
        byte[] file = CatalogImportHarness.single(CatalogImportSchemas.categories(), List.of(
                row("name", "Cháu", "parentName", "Con"), row("name", "Mới", "parentName", "Cũ")));

        ImportViews.RowsPage page = h.rows(h.categories, h.upload(h.categories, file));

        assertThat(codes(rowAt(page, "TheLoai", 2))).contains("CATEGORY_TOO_DEEP");
        assertThat(codes(rowAt(page, "TheLoai", 3))).contains("CATEGORY_INACTIVE");
    }

    @Test
    void theLoai_planChunksDatGocTruocCon() {
        ImportContext ctx = new ImportContext(CatalogImportHarness.TENANT, "alice", UUID.randomUUID(), java.util.Set.of(), false);
        List<ImportRow> valid = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            valid.add(ImportRow.of("TheLoai", i + 2, Map.of("name", "C" + i, "parentName", "R" + i)));
        }
        for (int i = 0; i < 5; i++) valid.add(ImportRow.of("TheLoai", i + 7, Map.of("name", "R" + i)));

        List<List<ImportRow>> chunks = h.categories.planChunks(ctx, valid, 4);

        List<ImportRow> flat = chunks.stream().flatMap(List::stream).toList();
        assertThat(flat).hasSize(10);
        assertThat(flat.subList(0, 5)).allMatch(r -> r.value("parentName").isEmpty());
        assertThat(flat.subList(5, 10)).allMatch(r -> !r.value("parentName").isEmpty());
        assertThat(chunks.stream().mapToInt(List::size)).containsExactly(4, 4, 2);
    }

    // ------------------------------------------------------------------ đầu sách + bản sao

    private void seedRefs() {
        h.references.seed(Reference.create(ReferenceKind.AUTHOR, "Nguyễn Nhật Ánh", null, null));
        h.references.seed(Reference.create(ReferenceKind.PUBLISHER, "NXB Trẻ", null, null));
        h.references.seed(Reference.create(ReferenceKind.CATEGORY, "Văn học", null, null));
        h.references.seed(Reference.create(ReferenceKind.SHELF_LOCATION, "A1-01", null, null));
    }

    private static Map<String, List<Map<String, Object>>> titles(List<Map<String, Object>> dauSach, List<Map<String, Object>> banSao) {
        Map<String, List<Map<String, Object>>> m = new java.util.LinkedHashMap<>();
        if (dauSach != null) m.put("DauSach", dauSach);
        if (banSao != null) m.put("BanSao", banSao);
        return m;
    }

    @Test
    void dauSach_validateTheoLoaiLoiThamChieuTrungVaCanhBao() {
        seedRefs();
        h.titles.seed(Title.create("Đã có", "X", "111", BigDecimal.TEN));
        byte[] file = CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), titles(List.of(
                row("isbn", "9786041234567", "name", "Mắt biếc", "author", "Nguyễn Nhật Ánh", "coverPrice", 95000,
                        "authors", "Nguyễn Nhật Ánh", "publisher", "NXB Trẻ", "category", "Văn học",
                        "publicationYear", 2019, "format", "paperback", "tags", "a;b;a"),            // 2 hợp lệ
                row("isbn", "9786041234567", "name", "Trùng file", "author", "A", "coverPrice", 1),   // 3 trùng trong file
                row("isbn", "111", "name", "Đã có", "author", "X", "coverPrice", 1),                  // 4 trùng DB
                row("isbn", "222", "name", "Thiếu NXB", "author", "A", "coverPrice", 1,
                        "publisher", "NXB Lạ", "authors", "Ai Đó"),                                    // 5 tham chiếu thiếu
                row("name", "Không ISBN", "author", "A", "coverPrice", 5),                            // 6 cảnh báo
                row("isbn", "333", "name", "Giá âm", "author", "A", "coverPrice", -1),                // 7 domain
                row("isbn", "444", "name", "Năm sai", "author", "A", "coverPrice", 1, "publicationYear", 500)), // 8
                null));

        ImportViews.RowsPage page = h.rows(h.titleHandler, h.upload(h.titleHandler, file));

        assertThat(rowAt(page, "DauSach", 2).status()).isEqualTo(RowStatus.VALID);
        assertThat(codes(rowAt(page, "DauSach", 3))).contains("DUPLICATE_IN_FILE");
        assertThat(rowAt(page, "DauSach", 4).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(codes(rowAt(page, "DauSach", 5))).contains("PUBLISHER_NOT_FOUND", "AUTHOR_NOT_FOUND");
        assertThat(rowAt(page, "DauSach", 6).status()).isEqualTo(RowStatus.VALID);
        assertThat(codes(rowAt(page, "DauSach", 6))).containsExactly("NO_DEDUPE_KEY");
        assertThat(rowAt(page, "DauSach", 7).status()).isEqualTo(RowStatus.INVALID);
        assertThat(codes(rowAt(page, "DauSach", 7))).contains("INVALID_TITLE");
        assertThat(codes(rowAt(page, "DauSach", 8))).contains("INVALID_TITLE");
    }

    @Test
    void dauSachVaBanSao_confirmGhiDauSachTruocBanSaoVaPhatSuKienSauCommit() {
        seedRefs();
        Title existing = Title.create("Đã có", "X", "111", BigDecimal.TEN);
        h.titles.seed(existing);
        h.copiesRepo.seed(BookCopy.register(existing.id(), "OLD-1"));
        byte[] file = CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), titles(
                List.of(row("isbn", "9786041234567", "name", "Mắt biếc", "author", "Nguyễn Nhật Ánh", "coverPrice", 95000,
                        "authors", "Nguyễn Nhật Ánh", "publisher", "NXB Trẻ", "category", "Văn học"),
                        row("isbn", "222", "name", "Sách hai", "author", "B", "coverPrice", 100)),
                List.of(row("isbn", "9786041234567", "barcode", "BC1", "shelfLocationCode", "a1-01",
                                "acquiredDate", "2024-01-15", "acquisitionSource", "PURCHASE", "purchasePrice", 80000,
                                "conditionGrade", "NEW"),                                                  // 2 hợp lệ
                        row("isbn", "9786041234567", "barcode", "BC2"),                                    // 3 hợp lệ
                        row("isbn", "111", "barcode", "BC3"),                                              // 4 gắn đầu sách đã có
                        row("isbn", "111", "barcode", "OLD-1"),                                            // 5 trùng DB
                        row("isbn", "999", "barcode", "BC4"),                                              // 6 không có đầu sách
                        row("isbn", "222", "barcode", "BC2"),                                              // 7 trùng file
                        row("isbn", "222", "barcode", "BC5", "shelfLocationCode", "Z9"),                   // 8 kệ không có
                        row("isbn", "222", "barcode", "BC6", "purchasePrice", 5))));                       // 9 giá mua không PURCHASE

        ImportViews.SessionView s = h.upload(h.titleHandler, file);
        ImportViews.RowsPage page = h.rows(h.titleHandler, s);
        assertThat(rowAt(page, "BanSao", 2).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, "BanSao", 3).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, "BanSao", 4).status()).isEqualTo(RowStatus.VALID);
        assertThat(rowAt(page, "BanSao", 5).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(codes(rowAt(page, "BanSao", 6))).contains("TITLE_NOT_FOUND");
        assertThat(codes(rowAt(page, "BanSao", 7))).contains("DUPLICATE_IN_FILE");
        assertThat(codes(rowAt(page, "BanSao", 8))).contains("SHELF_LOCATION_NOT_FOUND");
        assertThat(codes(rowAt(page, "BanSao", 9))).contains("INVALID_COPY");

        ImportViews.SessionView done = h.confirmAndWait(h.titleHandler, s.importId());

        assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(done.result().created()).isEqualTo(5); // 2 đầu sách + 3 bản sao
        Title created = h.titles.byIsbn("9786041234567");
        assertThat(created.publisherId()).isNotNull();
        assertThat(created.authorIds()).hasSize(1);
        assertThat(h.copiesRepo.byBarcode.get("BC1").titleId()).isEqualTo(created.id());
        assertThat(h.copiesRepo.byBarcode.get("BC1").shelfLocationId()).isNotNull();
        assertThat(h.copiesRepo.byBarcode.get("BC1").acquisition().purchasePrice()).isEqualTo(80000L);
        assertThat(h.copiesRepo.byBarcode.get("BC3").titleId()).isEqualTo(existing.id());
        assertThat(h.events).hasSize(3);
        assertThat(h.audits).containsExactly("titles:5/0/0");
    }

    @Test
    void banSao_dauSachCungFileInvalidThiBanSaoInvalid() {
        byte[] file = CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), titles(
                List.of(row("isbn", "555", "name", "Giá âm", "author", "A", "coverPrice", -5)),
                List.of(row("isbn", "555", "barcode", "X1"))));

        ImportViews.RowsPage page = h.rows(h.titleHandler, h.upload(h.titleHandler, file));

        assertThat(codes(rowAt(page, "BanSao", 2))).contains("TITLE_ROW_INVALID");
    }

    @Test
    void banSao_tenantKhongCoLendingBiLoiDongNhungDauSachVanNhapDuoc() {
        byte[] file = CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), titles(
                List.of(row("isbn", "777", "name", "Sách", "author", "A", "coverPrice", 1)),
                List.of(row("isbn", "777", "barcode", "L1"))));

        ImportViews.SessionView s = h.upload(h.titleHandler, h.actorNoLending, file);
        ImportViews.RowsPage page = h.workflow.rows(h.titleHandler, h.actorNoLending, s.importId(),
                new ImportViews.RowsQuery(null, null, 0, 50));

        assertThat(rowAt(page, "DauSach", 2).status()).isEqualTo(RowStatus.VALID);
        assertThat(codes(rowAt(page, "BanSao", 2))).contains("MODULE_NOT_ENABLED");
        h.confirmAndWait(h.titleHandler, h.actorNoLending, s.importId());
        assertThat(h.titles.byIsbn("777")).isNotNull();
        assertThat(h.copiesRepo.byBarcode).isEmpty();
    }

    @Test
    void dauSach_ghiDongThoiTrungIsbnChunkRollbackVaGanBanSaoVaoDauSachCuaNguoiKhac() {
        h.titles.raceIsbn = "888";
        byte[] file = CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), titles(
                List.of(row("isbn", "888", "name", "Đua", "author", "A", "coverPrice", 1),
                        row("isbn", "889", "name", "Không đua", "author", "A", "coverPrice", 1)),
                List.of(row("isbn", "888", "barcode", "R1"), row("isbn", "889", "barcode", "R2"))));
        ImportViews.SessionView s = h.upload(h.titleHandler, file);

        ImportViews.SessionView done = h.confirmAndWait(h.titleHandler, s.importId());

        assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(done.result().skippedDuplicate()).isEqualTo(1); // đầu sách 888 do người khác tạo
        assertThat(done.result().failed()).isEqualTo(0);
        assertThat(done.result().created()).isEqualTo(3);          // 889 + 2 bản sao
        assertThat(h.copiesRepo.byBarcode.get("R1").titleId()).isEqualTo(h.titles.byIsbn("888").id());
    }

    @Test
    void dauSach_planChunksGiuBanSaoCungDauSachVaBanSaoDauSachCoSanOChunkRieng() {
        ImportContext ctx = new ImportContext(CatalogImportHarness.TENANT, "alice", UUID.randomUUID(), java.util.Set.of("LENDING"), false);
        List<ImportRow> valid = new ArrayList<>();
        valid.add(ImportRow.of("DauSach", 2, Map.of("isbn", "A", "name", "a")));
        valid.add(ImportRow.of("DauSach", 3, Map.of("isbn", "B", "name", "b")));
        valid.add(ImportRow.of("DauSach", 4, Map.of("name", "không isbn")));
        valid.add(ImportRow.of("BanSao", 2, Map.of("isbn", "B", "barcode", "b1")));
        valid.add(ImportRow.of("BanSao", 3, Map.of("isbn", "OLD", "barcode", "o1")));
        valid.add(ImportRow.of("BanSao", 4, Map.of("isbn", "A", "barcode", "a1")));
        valid.add(ImportRow.of("BanSao", 5, Map.of("isbn", "A", "barcode", "a2")));

        List<List<ImportRow>> chunks = h.titleHandler.planChunks(ctx, valid, 2);

        // chunk 1: đầu sách A + a1 + a2 (nhóm không bị cắt); chunk 2: B + b1; chunk 3: đầu sách không ISBN;
        // chunk 4: bản sao của đầu sách đã có sẵn (OLD)
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0)).extracting(r -> r.value("isbn") + r.value("barcode")).containsExactly("A", "Aa1", "Aa2");
        assertThat(chunks.get(1)).extracting(r -> r.value("isbn") + r.value("barcode")).containsExactly("B", "Bb1");
        assertThat(chunks.get(2)).extracting(ImportRow::sheet).containsExactly("DauSach");
        assertThat(chunks.get(3)).extracting(r -> r.value("barcode")).containsExactly("o1");
        // xác định: gọi lại cho cùng kết quả
        assertThat(h.titleHandler.planChunks(ctx, valid, 2)).isEqualTo(chunks);
    }

    // ------------------------------------------------------------------ không N+1

    @Test
    void validateSoTruyVanKhongTangTheoSoDong() {
        seedRefs();
        int small = countLookups(10);
        int large = countLookups(600);
        assertThat(large).isEqualTo(small);
    }

    private int countLookups(int rows) {
        CatalogImportHarness hh = new CatalogImportHarness();
        try {
            hh.references.seed(Reference.create(ReferenceKind.AUTHOR, "Nguyễn Nhật Ánh", null, null));
            hh.references.seed(Reference.create(ReferenceKind.PUBLISHER, "NXB Trẻ", null, null));
            List<Map<String, Object>> dauSach = new ArrayList<>();
            List<Map<String, Object>> banSao = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                dauSach.add(row("isbn", "I" + i, "name", "S" + i, "author", "A", "coverPrice", 1,
                        "authors", "Nguyễn Nhật Ánh", "publisher", "NXB Trẻ"));
                banSao.add(row("isbn", "I" + i, "barcode", "B" + i));
            }
            hh.upload(hh.titleHandler, CatalogImportHarness.xlsx(CatalogImportSchemas.titles(), titles(dauSach, banSao)));
            return hh.references.findByNameKeysCalls.get() * 10_000 + hh.titles.isbnLookups.get() * 100
                    + hh.copiesRepo.barcodeLookups.get();
        } finally {
            hh.close();
        }
    }

    @Test
    void commitSoTruyVanTheoChunkKhongTangTheoSoDong() {
        int small = commitLookups(5);
        int large = commitLookups(150); // một chunk 200 dòng gồm 150 đầu sách + 150 bản sao
        assertThat(large).isEqualTo(small);
    }

    private int commitLookups(int rows) {
        CatalogImportHarness hh = new CatalogImportHarness();
        try {
            List<ImportRow> chunk = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                chunk.add(ImportRow.of("DauSach", i + 2, Map.of("isbn", "K" + i, "name", "S" + i, "author", "A", "coverPrice", "1")));
                chunk.add(ImportRow.of("BanSao", i + 2, Map.of("isbn", "K" + i, "barcode", "BK" + i)));
            }
            ImportContext ctx = new ImportContext(CatalogImportHarness.TENANT, "alice", UUID.randomUUID(), java.util.Set.of("LENDING"), false);

            var result = hh.titleHandler.commitChunk(ctx, chunk);

            assertThat(result.outcomes()).hasSize(chunk.size());
            assertThat(hh.titles.byId).hasSize(rows);
            assertThat(hh.copiesRepo.byBarcode).hasSize(rows);
            return hh.references.findByNameKeysCalls.get() * 10_000 + hh.titles.isbnLookups.get() * 100
                    + hh.copiesRepo.barcodeLookups.get();
        } finally {
            hh.close();
        }
    }

    @Test
    void schemaCacLoaiDungTenSheetVaKhoaCot() {
        assertThat(CatalogImportSchemas.titles().sheets()).extracting(s -> s.name()).containsExactly("DauSach", "BanSao");
        assertThat(CatalogImportSchemas.shelfLocations().sheets().get(0).column("code").required()).isTrue();
        assertThat(CatalogImportSchemas.categories().sheets().get(0).column("parentName")).isNotNull();
        assertThat(ReferenceExtras.EMPTY.isEmpty()).isTrue();
    }
}
