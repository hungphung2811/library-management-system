package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.catalog.catalog.application.CreateReferenceUseCase;
import lms.catalog.catalog.application.ReferenceOptionsUseCase;
import lms.catalog.catalog.application.ReferenceResponse;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.SearchReferencesUseCase;
import lms.catalog.catalog.application.SetReferenceStatusUseCase;
import lms.catalog.catalog.application.TitleClassificationService;
import lms.catalog.catalog.application.UpdateReferenceUseCase;
import lms.catalog.catalog.domain.CategoryHierarchyException;
import lms.catalog.catalog.domain.DuplicateReferenceException;
import lms.catalog.catalog.domain.InactiveReferenceException;
import lms.catalog.catalog.domain.InvalidReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferenceUseCasesTest {

    /** Kho trong bộ nhớ (một tenant). */
    static class FakeRepo implements ReferenceRepository {
        final Map<UUID, Reference> data = new LinkedHashMap<>();

        @Override
        public Reference save(Reference r) {
            data.put(r.id(), r);
            return r;
        }

        @Override
        public Optional<Reference> findById(ReferenceKind kind, UUID id) {
            return Optional.ofNullable(data.get(id)).filter(r -> r.kind() == kind);
        }

        @Override
        public List<Reference> findByIds(ReferenceKind kind, Collection<UUID> ids) {
            return ids.stream().map(data::get).filter(r -> r != null && r.kind() == kind).toList();
        }

        @Override
        public boolean existsByNameKey(ReferenceKind kind, String nameKey, UUID excludeId) {
            return data.values().stream().anyMatch(r -> r.kind() == kind && r.nameKey().equals(nameKey)
                    && !r.id().equals(excludeId));
        }

        @Override
        public boolean hasChildren(UUID categoryId) {
            return data.values().stream().anyMatch(r -> categoryId.equals(r.parentId()));
        }

        @Override
        public Page search(ReferenceKind kind, String keyword, Boolean active, UUID parentId, int page, int size) {
            List<Reference> all = data.values().stream().filter(r -> r.kind() == kind)
                    .filter(r -> keyword == null || r.nameKey().contains(keyword.toLowerCase()))
                    .filter(r -> active == null || r.active() == active)
                    .filter(r -> parentId == null || parentId.equals(r.parentId()))
                    .sorted(java.util.Comparator.comparing(Reference::nameKey)).toList();
            int from = Math.min(page * size, all.size());
            return new Page(all.subList(from, Math.min(from + size, all.size())), all.size());
        }

        @Override
        public List<Reference> options(ReferenceKind kind, String keyword, int limit) {
            return new ArrayList<>(search(kind, keyword, true, null, 0, limit).items());
        }
    }

    FakeRepo repo;
    CreateReferenceUseCase create;
    UpdateReferenceUseCase update;
    SetReferenceStatusUseCase setStatus;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        create = new CreateReferenceUseCase(repo);
        update = new UpdateReferenceUseCase(repo);
        setStatus = new SetReferenceStatusUseCase(repo);
    }

    @Test
    void chuanHoaTenVaChanTrungKhongPhanBietHoaThuongVaKhoangTrang() {
        ReferenceResponse a = create.execute(ReferenceKind.AUTHOR, "  Nguyễn   Nhật Ánh ", " mô tả ", null);
        assertEquals("Nguyễn Nhật Ánh", a.name());
        assertEquals("mô tả", a.description());
        assertTrue(a.active());
        assertThrows(DuplicateReferenceException.class,
                () -> create.execute(ReferenceKind.AUTHOR, "nguyễn nhật ÁNH", null, null));
        // Cùng tên ở danh mục khác không trùng
        create.execute(ReferenceKind.PUBLISHER, "Nguyễn Nhật Ánh", null, null);
    }

    @Test
    void tenTrongVaMoTaQuaDaiBiTuChoi() {
        assertThrows(InvalidReferenceException.class, () -> create.execute(ReferenceKind.AUTHOR, "   ", null, null));
        assertThrows(InvalidReferenceException.class,
                () -> create.execute(ReferenceKind.AUTHOR, "A", "x".repeat(501), null));
        assertThrows(InvalidReferenceException.class,
                () -> create.execute(ReferenceKind.SHELF_LOCATION, "K".repeat(65), null, null));
        assertThrows(InvalidReferenceException.class,
                () -> create.execute(ReferenceKind.AUTHOR, "A", null, UUID.randomUUID()), "chỉ thể loại có cha");
    }

    @Test
    void suaTenKhongBiTinhLaTrungChinhMinhNhungTrungMucKhacThiBi() {
        ReferenceResponse a = create.execute(ReferenceKind.PUBLISHER, "Kim Đồng", null, null);
        create.execute(ReferenceKind.PUBLISHER, "Trẻ", null, null);
        assertEquals("KIM ĐỒNG", update.execute(ReferenceKind.PUBLISHER, a.id(), "KIM ĐỒNG", "mới", null).name());
        assertThrows(DuplicateReferenceException.class,
                () -> update.execute(ReferenceKind.PUBLISHER, a.id(), "trẻ", null, null));
        assertThrows(ReferenceNotFoundException.class,
                () -> update.execute(ReferenceKind.PUBLISHER, UUID.randomUUID(), "X", null, null));
    }

    @Test
    void theLoaiToiDaHaiCapVaKhongVong() {
        ReferenceResponse root = create.execute(ReferenceKind.CATEGORY, "Văn học", null, null);
        ReferenceResponse child = create.execute(ReferenceKind.CATEGORY, "Tiểu thuyết", null, root.id());
        assertEquals("Văn học", child.parentName());

        // Cấp 3
        CategoryHierarchyException tooDeep = assertThrows(CategoryHierarchyException.class,
                () -> create.execute(ReferenceKind.CATEGORY, "Trinh thám", null, child.id()));
        assertEquals("CATEGORY_TOO_DEEP", tooDeep.code());
        // Cha là chính nó
        assertEquals("CATEGORY_CYCLE", assertThrows(CategoryHierarchyException.class,
                () -> update.execute(ReferenceKind.CATEGORY, root.id(), "Văn học", null, root.id())).code());
        // Đảo: gốc đang có con không thể làm con của con (vòng) -> chặn
        assertThrows(CategoryHierarchyException.class,
                () -> update.execute(ReferenceKind.CATEGORY, root.id(), "Văn học", null, child.id()));
        // Gốc có con không thể thành con của thể loại gốc khác (sẽ thành 3 cấp)
        ReferenceResponse other = create.execute(ReferenceKind.CATEGORY, "Khoa học", null, null);
        assertEquals("CATEGORY_TOO_DEEP", assertThrows(CategoryHierarchyException.class,
                () -> update.execute(ReferenceKind.CATEGORY, root.id(), "Văn học", null, other.id())).code());
        // Gỡ cha được
        assertNull(update.execute(ReferenceKind.CATEGORY, child.id(), "Tiểu thuyết", null, null).parentId());
    }

    @Test
    void doiTrangThaiIdempotentVaOptionsChiLayMucDangDung() {
        ReferenceResponse a = create.execute(ReferenceKind.AUTHOR, "An", null, null);
        create.execute(ReferenceKind.AUTHOR, "Bình", null, null);
        assertFalse(setStatus.execute(ReferenceKind.AUTHOR, a.id(), false).active());
        assertFalse(setStatus.execute(ReferenceKind.AUTHOR, a.id(), false).active());
        var options = new ReferenceOptionsUseCase(repo).execute(ReferenceKind.AUTHOR, null);
        assertEquals(1, options.size());
        assertEquals("Bình", options.get(0).name());
        var page = new SearchReferencesUseCase(repo).execute(ReferenceKind.AUTHOR,
                new ReferenceSearchQuery(null, null, null, 0, 1));
        assertEquals(2, page.total());
        assertEquals(1, page.items().size());
    }

    @Test
    void khongGanTheLoaiChaDaNgungDungKhiTaoMoi() {
        ReferenceResponse root = create.execute(ReferenceKind.CATEGORY, "Cũ", null, null);
        setStatus.execute(ReferenceKind.CATEGORY, root.id(), false);
        assertThrows(InactiveReferenceException.class,
                () -> create.execute(ReferenceKind.CATEGORY, "Con", null, root.id()));
    }

    @Test
    void phanLoaiDauSachKiemTonTaiVaActiveNhungGiuGiaTriCu() {
        TitleClassificationService svc = new TitleClassificationService(repo);
        ReferenceResponse author = create.execute(ReferenceKind.AUTHOR, "Tác giả A", null, null);
        ReferenceResponse pub = create.execute(ReferenceKind.PUBLISHER, "NXB A", null, null);
        Title base = Title.create("Sách", "Tác giả A", null, java.math.BigDecimal.TEN);

        Title t = svc.classify(base, pub.id(), null, List.of(author.id(), author.id()));
        assertEquals(List.of(author.id()), t.authorIds(), "trùng tác giả được gộp");
        assertEquals(pub.id(), t.publisherId());
        assertEquals("Tác giả A", svc.describe(t).authors().get(0).name());

        assertThrows(ReferenceNotFoundException.class,
                () -> svc.classify(base, UUID.randomUUID(), null, null));
        assertThrows(ReferenceNotFoundException.class,
                () -> svc.classify(base, null, null, List.of(UUID.randomUUID())));

        setStatus.execute(ReferenceKind.AUTHOR, author.id(), false);
        setStatus.execute(ReferenceKind.PUBLISHER, pub.id(), false);
        assertThrows(InactiveReferenceException.class, () -> svc.classify(base, null, null, List.of(author.id())));
        assertThrows(InactiveReferenceException.class, () -> svc.classify(base, pub.id(), null, null));
        // Đầu sách đã mang sẵn tác giả/NXB ngừng dùng thì giữ nguyên được
        Title kept = svc.classify(t, pub.id(), null, List.of(author.id()));
        assertEquals(pub.id(), kept.publisherId());
    }
}
