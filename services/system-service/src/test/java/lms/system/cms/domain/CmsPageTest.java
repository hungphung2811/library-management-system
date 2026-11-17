package lms.system.cms.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CmsPageTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");
    private static final UUID ADMIN = UUID.randomUUID();

    private static CmsPage draft() {
        return CmsPage.create(UUID.randomUUID(), "gioi-thieu", "Giới thiệu", "# Xin chào", 10, ADMIN, T0);
    }

    private static CmsErrorCode codeOf(Runnable action) {
        CmsException ex = (CmsException) assertThatThrownBy(action::run).isInstanceOf(CmsException.class).actual();
        return ex.code();
    }

    @Test
    void taoMoiLaNhapChuaXuatBan() {
        CmsPage page = draft();

        assertThat(page.status()).isEqualTo(CmsPageStatus.DRAFT);
        assertThat(page.isPublished()).isFalse();
        assertThat(page.publishedTitle()).isNull();
        assertThat(page.publishedBodyMarkdown()).isNull();
        assertThat(page.version()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Gioi-Thieu", "gioi thieu", "gioi_thieu", "giới-thiệu", "a/b", "../x"})
    void slugSaiDinhDangBiTuChoi(String slug) {
        assertThat(codeOf(() -> CmsPage.create(UUID.randomUUID(), slug, "T", "b", 0, ADMIN, T0)))
                .isEqualTo(CmsErrorCode.CMS_SLUG_INVALID);
    }

    @Test
    void slugQuaDaiVaNullBiTuChoi() {
        assertThat(codeOf(() -> CmsPage.create(UUID.randomUUID(), "a".repeat(81), "T", "b", 0, ADMIN, T0)))
                .isEqualTo(CmsErrorCode.CMS_SLUG_INVALID);
        assertThat(codeOf(() -> CmsPage.create(UUID.randomUUID(), null, "T", "b", 0, ADMIN, T0)))
                .isEqualTo(CmsErrorCode.CMS_SLUG_INVALID);
        assertThat(CmsPage.create(UUID.randomUUID(), "a".repeat(80), "T", "b", 0, ADMIN, T0).slug()).hasSize(80);
    }

    @Test
    void noiDungQuaDaiBiTuChoiVaDungNguongChoPhep() {
        assertThat(codeOf(() -> CmsPage.create(UUID.randomUUID(), "a", "T", "x".repeat(50_001), 0, ADMIN, T0)))
                .isEqualTo(CmsErrorCode.CMS_CONTENT_TOO_LONG);
        assertThat(CmsPage.create(UUID.randomUUID(), "a", "T", "x".repeat(50_000), 0, ADMIN, T0)).isNotNull();
    }

    @Test
    void tieuDeRongHoacQuaDaiBiTuChoi() {
        assertThat(codeOf(() -> CmsPage.create(UUID.randomUUID(), "a", "  ", "b", 0, ADMIN, T0)))
                .isEqualTo(CmsErrorCode.CMS_PAGE_INVALID);
        assertThat(codeOf(() -> CmsPage.create(UUID.randomUUID(), "a", "t".repeat(201), "b", 0, ADMIN, T0)))
                .isEqualTo(CmsErrorCode.CMS_PAGE_INVALID);
    }

    @Test
    void xuatBanChepBanLamViecSangBanXuatBan() {
        CmsPage published = draft().publish(ADMIN, T1);

        assertThat(published.status()).isEqualTo(CmsPageStatus.PUBLISHED);
        assertThat(published.publishedTitle()).isEqualTo("Giới thiệu");
        assertThat(published.publishedBodyMarkdown()).isEqualTo("# Xin chào");
        assertThat(published.publishedAt()).isEqualTo(T1);
    }

    @Test
    void suaSauKhiXuatBanKhongDoiBanDaXuatBan() {
        CmsPage edited = draft().publish(ADMIN, T0).edit("Tiêu đề mới", "Nội dung đang sửa", 5, ADMIN, T1);

        assertThat(edited.status()).isEqualTo(CmsPageStatus.PUBLISHED_WITH_CHANGES);
        assertThat(edited.title()).isEqualTo("Tiêu đề mới");
        assertThat(edited.publishedTitle()).isEqualTo("Giới thiệu");
        assertThat(edited.publishedBodyMarkdown()).isEqualTo("# Xin chào");
        assertThat(edited.slug()).isEqualTo("gioi-thieu");
    }

    @Test
    void goXuatBanXoaBanXuatBanGiuBanLamViec() {
        CmsPage page = draft().publish(ADMIN, T0).unpublish(ADMIN, T1);

        assertThat(page.status()).isEqualTo(CmsPageStatus.DRAFT);
        assertThat(page.publishedAt()).isNull();
        assertThat(page.publishedBodyMarkdown()).isNull();
        assertThat(page.title()).isEqualTo("Giới thiệu");
    }

    @Test
    void khoaLacQuanBaoLechPhienBan() {
        CmsPage page = draft();

        page.requireVersion(0);
        assertThat(codeOf(() -> page.requireVersion(3))).isEqualTo(CmsErrorCode.CMS_VERSION_CONFLICT);
    }
}
