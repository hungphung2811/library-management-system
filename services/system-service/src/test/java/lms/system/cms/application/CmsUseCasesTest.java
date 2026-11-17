package lms.system.cms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lms.system.cms.domain.BusinessType;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.CmsPage;
import lms.system.cms.domain.CmsPageStatus;
import lms.system.cms.domain.ContactRequest;
import lms.system.cms.domain.ContactStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CmsUseCasesTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private FakePages pages;
    private FakeContacts contacts;
    private ManageCmsPagesUseCase managePages;
    private PublishedCmsPagesUseCase published;
    private SubmitContactRequestUseCase submit;
    private ManageContactRequestsUseCase manageContacts;

    @BeforeEach
    void setUp() {
        pages = new FakePages();
        contacts = new FakeContacts();
        managePages = new ManageCmsPagesUseCase(pages, clock);
        published = new PublishedCmsPagesUseCase(pages, md -> "<html>" + md + "</html>");
        submit = new SubmitContactRequestUseCase(contacts, clock);
        manageContacts = new ManageContactRequestsUseCase(contacts, clock);
    }

    private CmsPage newPage(String slug) {
        return managePages.create(new ManageCmsPagesUseCase.CreateCommand(slug, "Tiêu đề " + slug, "Nội dung " + slug,
                10, ADMIN));
    }

    private static CmsErrorCode codeOf(Runnable action) {
        CmsException ex = (CmsException) assertThatThrownBy(action::run).isInstanceOf(CmsException.class).actual();
        return ex.code();
    }

    // ---------- trang ----------

    @Test
    void slugTrungBiTuChoi() {
        newPage("gioi-thieu");

        assertThat(codeOf(() -> newPage("gioi-thieu"))).isEqualTo(CmsErrorCode.CMS_SLUG_DUPLICATED);
    }

    @Test
    void banNhapKhongBaoGioLoRaCongKhai() {
        newPage("nhap");

        assertThat(published.list()).isEmpty();
        assertThat(codeOf(() -> published.get("nhap"))).isEqualTo(CmsErrorCode.CMS_PAGE_NOT_FOUND);
    }

    @Test
    void xuatBanRoiSuaThiCongKhaiVanLaBanDaXuatBan() {
        CmsPage page = newPage("gioi-thieu");
        managePages.publish(page.id(), page.version(), ADMIN);
        CmsPage afterPublish = managePages.get(page.id());
        managePages.update(new ManageCmsPagesUseCase.UpdateCommand(page.id(), "Tiêu đề NHÁP", "Nội dung NHÁP", 1,
                afterPublish.version(), ADMIN));

        PublishedCmsPagesUseCase.Published view = published.get("gioi-thieu");
        assertThat(view.title()).isEqualTo("Tiêu đề gioi-thieu");
        assertThat(view.bodyHtml()).contains("Nội dung gioi-thieu").doesNotContain("NHÁP");
        assertThat(published.list()).extracting(PublishedCmsPagesUseCase.Summary::title)
                .containsExactly("Tiêu đề gioi-thieu");
        assertThat(managePages.get(page.id()).status()).isEqualTo(CmsPageStatus.PUBLISHED_WITH_CHANGES);
    }

    @Test
    void goXuatBanThiTrangBienMatKhoiCongKhai() {
        CmsPage page = newPage("a");
        managePages.publish(page.id(), page.version(), ADMIN);

        managePages.unpublish(page.id(), ADMIN);

        assertThat(codeOf(() -> published.get("a"))).isEqualTo(CmsErrorCode.CMS_PAGE_NOT_FOUND);
        assertThat(managePages.get(page.id()).status()).isEqualTo(CmsPageStatus.DRAFT);
    }

    @Test
    void xuatBanVoiPhienBanCuBi409() {
        CmsPage page = newPage("a");
        managePages.update(new ManageCmsPagesUseCase.UpdateCommand(page.id(), "Mới", "Mới", 0, page.version(), ADMIN));

        assertThat(codeOf(() -> managePages.publish(page.id(), page.version(), ADMIN)))
                .isEqualTo(CmsErrorCode.CMS_VERSION_CONFLICT);
        assertThat(published.list()).isEmpty();
    }

    @Test
    void suaVoiPhienBanCuBi409() {
        CmsPage page = newPage("a");
        managePages.update(new ManageCmsPagesUseCase.UpdateCommand(page.id(), "Một", "Một", 0, page.version(), ADMIN));

        assertThat(codeOf(() -> managePages.update(new ManageCmsPagesUseCase.UpdateCommand(page.id(), "Hai", "Hai",
                0, page.version(), ADMIN)))).isEqualTo(CmsErrorCode.CMS_VERSION_CONFLICT);
    }

    @Test
    void danhSachCongKhaiTheoSortOrderRoiSlug() {
        for (String[] spec : new String[][] {{"c", "1"}, {"b", "1"}, {"a", "2"}}) {
            CmsPage p = managePages.create(new ManageCmsPagesUseCase.CreateCommand(spec[0], "T" + spec[0], "b",
                    Integer.parseInt(spec[1]), ADMIN));
            managePages.publish(p.id(), p.version(), ADMIN);
        }

        assertThat(published.list()).extracting(PublishedCmsPagesUseCase.Summary::slug).containsExactly("b", "c", "a");
    }

    @Test
    void xoaTrangBienMatKhoiCongKhai() {
        CmsPage page = newPage("x");
        managePages.publish(page.id(), page.version(), ADMIN);

        managePages.delete(page.id());

        assertThat(codeOf(() -> managePages.get(page.id()))).isEqualTo(CmsErrorCode.CMS_PAGE_NOT_FOUND);
        assertThat(published.list()).isEmpty();
        assertThat(codeOf(() -> managePages.delete(page.id()))).isEqualTo(CmsErrorCode.CMS_PAGE_NOT_FOUND);
    }

    // ---------- yêu cầu liên hệ ----------

    private UUID submitContact(String name) {
        return submit.execute(new SubmitContactRequestUseCase.Command(name, name + "@example.com", null, "Tổ chức",
                BusinessType.BOOKSTORE, "Lời nhắn", true));
    }

    @Test
    void thieuDongYThiKhongLuu() {
        assertThat(codeOf(() -> submit.execute(new SubmitContactRequestUseCase.Command("A", "a@b.co", null, "O",
                BusinessType.OTHER, "m", false)))).isEqualTo(CmsErrorCode.CONSENT_REQUIRED);
        assertThat(contacts.store).isEmpty();
    }

    @Test
    void doiTrangThaiVaVersion() {
        UUID id = submitContact("a");

        ContactRequest changed = manageContacts.changeStatus(id, ContactStatus.HANDLED, 0, ADMIN);

        assertThat(changed.status()).isEqualTo(ContactStatus.HANDLED);
        assertThat(changed.handledBy()).isEqualTo(ADMIN);
        assertThat(codeOf(() -> manageContacts.changeStatus(id, ContactStatus.IN_PROGRESS, 0, ADMIN)))
                .isEqualTo(CmsErrorCode.CONTACT_REQUEST_VERSION_CONFLICT);
    }

    @Test
    void anDanhNgayVaIdempotent() {
        UUID id = submitContact("a");

        ContactRequest first = manageContacts.anonymize(id);
        now.set(START.plus(Duration.ofDays(5)));
        ContactRequest second = manageContacts.anonymize(id);

        assertThat(first.email()).isNull();
        assertThat(second.anonymizedAt()).isEqualTo(first.anonymizedAt());
        assertThat(codeOf(() -> manageContacts.changeStatus(id, ContactStatus.HANDLED, second.version(), ADMIN)))
                .isEqualTo(CmsErrorCode.CONTACT_REQUEST_ANONYMIZED);
        assertThat(codeOf(() -> manageContacts.anonymize(UUID.randomUUID())))
                .isEqualTo(CmsErrorCode.CONTACT_REQUEST_NOT_FOUND);
    }

    // ---------- job ẩn danh ----------

    @Test
    void jobChiAnDanhDongQuaHanMoiTrangThaiVaChayLaiKhongDoi() {
        UUID old1 = submitContact("old1");
        manageContacts.changeStatus(old1, ContactStatus.HANDLED, 0, ADMIN);
        UUID old2 = submitContact("old2"); // NEW chưa xử lý vẫn bị ẩn danh
        now.set(START.plus(Duration.ofDays(200)));
        UUID fresh = submitContact("fresh");
        AnonymizeExpiredContactRequestsUseCase job = new AnonymizeExpiredContactRequestsUseCase(contacts, clock,
                Duration.ofDays(365), 10);

        now.set(START.plus(Duration.ofDays(366)));
        assertThat(job.anonymizeBatch()).isEqualTo(2);

        assertThat(contacts.store.get(old1).isAnonymized()).isTrue();
        assertThat(contacts.store.get(old2).isAnonymized()).isTrue();
        assertThat(contacts.store.get(old1).organization()).isEqualTo("Tổ chức");
        assertThat(contacts.store.get(old1).status()).isEqualTo(ContactStatus.HANDLED);
        assertThat(contacts.store.get(fresh).isAnonymized()).isFalse();
        Instant stamped = contacts.store.get(old1).anonymizedAt();

        // chạy lại: không còn gì để làm và không đổi mốc ẩn danh
        now.set(START.plus(Duration.ofDays(367)));
        assertThat(job.anonymizeBatch()).isZero();
        assertThat(contacts.store.get(old1).anonymizedAt()).isEqualTo(stamped);
    }

    @Test
    void jobXuLyTheoLoVaBaoHetKhiSoDongNhoHonCoLo() {
        for (int i = 0; i < 5; i++) {
            submitContact("u" + i);
        }
        AnonymizeExpiredContactRequestsUseCase job = new AnonymizeExpiredContactRequestsUseCase(contacts, clock,
                Duration.ofDays(1), 2);
        now.set(START.plus(Duration.ofDays(10)));

        assertThat(job.anonymizeBatch()).isEqualTo(2);
        assertThat(job.anonymizeBatch()).isEqualTo(2);
        assertThat(job.anonymizeBatch()).isEqualTo(1);
        assertThat(job.anonymizeBatch()).isZero();
    }

    @Test
    void jobTuChoiCauHinhSai() {
        assertThatThrownBy(() -> new AnonymizeExpiredContactRequestsUseCase(contacts, clock, Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnonymizeExpiredContactRequestsUseCase(contacts, clock, Duration.ofDays(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- fakes ----------

    private static final class FakePages implements CmsPageRepositoryPort {
        final Map<UUID, CmsPage> store = new LinkedHashMap<>();

        @Override public Optional<CmsPage> findById(UUID id) { return Optional.ofNullable(store.get(id)); }

        @Override public Optional<CmsPage> findBySlug(String slug) {
            return store.values().stream().filter(p -> p.slug().equals(slug)).findFirst();
        }

        private List<CmsPage> sorted(boolean onlyPublished) {
            return store.values().stream().filter(p -> !onlyPublished || p.isPublished())
                    .sorted(Comparator.comparingInt(CmsPage::sortOrder).thenComparing(CmsPage::slug)).toList();
        }

        @Override public List<CmsPage> findAll() { return sorted(false); }
        @Override public List<CmsPage> findPublished() { return sorted(true); }

        @Override public Optional<CmsPage> findPublishedBySlug(String slug) {
            return findBySlug(slug).filter(CmsPage::isPublished);
        }

        /** Giống JPA: tăng version khi ghi lại dòng đã có. */
        @Override public CmsPage save(CmsPage p) {
            CmsPage existing = store.get(p.id());
            long version = existing == null ? p.version() : existing.version() + 1;
            CmsPage saved = new CmsPage(p.id(), p.slug(), p.title(), p.bodyMarkdown(), p.publishedTitle(),
                    p.publishedBodyMarkdown(), p.publishedAt(), p.sortOrder(), version, p.createdAt(), p.updatedAt(),
                    p.updatedBy());
            store.put(p.id(), saved);
            return saved;
        }

        @Override public void deleteById(UUID id) { store.remove(id); }
    }

    private static final class FakeContacts implements ContactRequestRepositoryPort {
        final Map<UUID, ContactRequest> store = new LinkedHashMap<>();

        @Override public ContactRequest save(ContactRequest r) {
            ContactRequest existing = store.get(r.id());
            long version = existing == null ? r.version() : existing.version() + 1;
            ContactRequest saved = new ContactRequest(r.id(), r.fullName(), r.email(), r.phone(), r.organization(),
                    r.businessType(), r.message(), r.consentAt(), r.status(), version, r.createdAt(), r.handledBy(),
                    r.handledAt(), r.anonymizedAt());
            store.put(r.id(), saved);
            return saved;
        }

        @Override public Optional<ContactRequest> findById(UUID id) { return Optional.ofNullable(store.get(id)); }

        @Override public Page<ContactRequest> search(Criteria c) {
            return new Page<>(new ArrayList<>(store.values()), c.page(), c.size(), store.size());
        }

        @Override public List<ContactRequest> findDueForAnonymization(Instant cutoff, int limit) {
            return store.values().stream().filter(r -> r.isDueForAnonymization(cutoff))
                    .sorted(Comparator.comparing(ContactRequest::createdAt)).limit(limit).toList();
        }
    }
}
