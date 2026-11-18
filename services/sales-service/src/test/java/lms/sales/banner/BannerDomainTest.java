package lms.sales.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lms.sales.banner.domain.Banner;
import lms.sales.banner.domain.BannerLimits;
import lms.sales.banner.domain.BannerLink;
import lms.sales.banner.domain.BannerStatus;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BannerDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final UUID T = UUID.randomUUID();

    private static Banner banner(String title, String subtitle, String link, Integer sort, Boolean active,
                                 Instant from, Instant to) {
        return Banner.create(UUID.randomUUID(), T, title, subtitle, link, sort, active, from, to, null, NOW);
    }

    // ---- linkPath ----

    @ParameterizedTest
    @ValueSource(strings = {"/", "/books", "/books?sort=newest", "/books/5b0c1d2e-0000-4000-8000-000000000000",
            "/books?a=1&b=2#top", "/a/b-c_d.e~f", "/search?q=nh%C3%A0%20gi%E1%BA%A3", "/a,b+c", "/a..b", "/.hidden"})
    void linkHopLeDuocGiuNguyen(String link) {
        assertThat(BannerLink.parse(link)).isEqualTo(link);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void linkRongThanhNull(String link) {
        assertThat(BannerLink.parse(link)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.com", "//x", "/\\x", "/a\\b", "\\x", "/a/../b", "/..", "/a/..", "/../a",
            "/a/..?x=1", "/%2e%2e/a", "/%2E./a", "/.%2e", "javascript:alert(1)", "data:text/html,x", "http://a.b",
            "https://a.b/x", "mailto:a@b.c", "books", "?x=1", "#a", "/%zz", "/%2", "/%", "/a%", "/a%g1", "/a b",
            " /a", "/a ", "/a\tb", "/a\nb", "/a\u0000b", "/a\u007fb", "/sách", "/a​b", "/a'b", "/a\"b", "/a<b",
            "/a>b", "/a(b)", "/a;b", "/a:b", "/a@b", "/a/b//c", "/a?u=//x", "/a|b", "/a`b", "/a{b}", "/a^b",
            "/a!b", "/a$b", "/a*b"})
    void linkSaiBiTuChoi(String link) {
        assertThatThrownBy(() -> BannerLink.parse(link)).isInstanceOfSatisfying(SalesException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_BANNER);
            assertThat(e.details()).containsEntry("field", "linkPath");
            assertThat(e.getMessage()).doesNotContain(link.strip().isEmpty() ? "?" : link);
        });
    }

    @Test
    void linkDai200LaBienHopLe201BiTuChoi() {
        String ok = "/" + "a".repeat(199);
        assertThat(BannerLink.parse(ok)).hasSize(200);
        assertThatThrownBy(() -> BannerLink.parse(ok + "a")).isInstanceOf(SalesException.class);
    }

    // ---- văn bản ----

    @Test
    void tieuDeChuanHoaNfcVaCatKhoangTrang() {
        String decomposed = "Nhầ giả kim"; // dạng NFD
        Banner b = banner("  " + decomposed + "  ", "  phụ đề  ", null, null, null, null, null);
        assertThat(b.title()).isEqualTo(java.text.Normalizer.normalize(decomposed, java.text.Normalizer.Form.NFC));
        assertThat(b.subtitle()).isEqualTo("phụ đề");
    }

    @Test
    void phuDeRongThanhNull() {
        assertThat(banner("A", "   ", null, null, null, null, null).subtitle()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "a\nb", "a\tb", "a\u0000b", "a\rb"})
    void tieuDeSaiBiTuChoi(String title) {
        assertThatThrownBy(() -> banner(title, null, null, null, null, null, null))
                .isInstanceOfSatisfying(SalesException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_BANNER);
                    assertThat(e.details()).containsEntry("field", "title");
                });
    }

    @Test
    void doDaiTinhTheoCodePoint() {
        String emoji100 = "😀".repeat(100); // 200 char, 100 code point
        assertThat(banner(emoji100, null, null, null, null, null, null).title()).isEqualTo(emoji100);
        assertThatThrownBy(() -> banner("a".repeat(101), null, null, null, null, null, null))
                .isInstanceOf(SalesException.class);
        assertThat(banner("A", "b".repeat(200), null, null, null, null, null).subtitle()).hasSize(200);
        assertThatThrownBy(() -> banner("A", "b".repeat(201), null, null, null, null, null))
                .isInstanceOfSatisfying(SalesException.class,
                        e -> assertThat(e.details()).containsEntry("field", "subtitle"));
    }

    @Test
    void sortOrderMacDinh0VaGioiHan() {
        assertThat(banner("A", null, null, null, null, null, null).sortOrder()).isZero();
        assertThat(banner("A", null, null, 999, null, null, null).sortOrder()).isEqualTo(999);
        assertThatThrownBy(() -> banner("A", null, null, 1000, null, null, null))
                .isInstanceOfSatisfying(SalesException.class,
                        e -> assertThat(e.details()).containsEntry("field", "sortOrder"));
        assertThatThrownBy(() -> banner("A", null, null, -1, null, null, null)).isInstanceOf(SalesException.class);
    }

    @Test
    void activeMacDinhTrue() {
        assertThat(banner("A", null, null, null, null, null, null).active()).isTrue();
        assertThat(banner("A", null, null, null, false, null, null).active()).isFalse();
    }

    // ---- thời gian ----

    @Test
    void cuaSoThoiGianPhaiStartsTruocEnds() {
        assertThatThrownBy(() -> banner("A", null, null, null, null, NOW, NOW))
                .isInstanceOfSatisfying(SalesException.class,
                        e -> assertThat(e.details()).containsEntry("field", "endsAt"));
        assertThatThrownBy(() -> banner("A", null, null, null, null, NOW, NOW.minusSeconds(1)))
                .isInstanceOf(SalesException.class);
        banner("A", null, null, null, null, NOW, NOW.plusSeconds(1));
        banner("A", null, null, null, null, NOW, null);
        banner("A", null, null, null, null, null, NOW);
    }

    @Test
    void hieuLucBienStartsBangNowVaEndsBangNow() {
        Banner startsNow = banner("A", null, null, null, null, NOW, NOW.plus(Duration.ofDays(1)));
        assertThat(startsNow.isLiveAt(NOW)).isTrue();
        assertThat(startsNow.isLiveAt(NOW.minusMillis(1))).isFalse();
        assertThat(startsNow.statusAt(NOW.minusMillis(1))).isEqualTo(BannerStatus.SCHEDULED);

        Banner endsNow = banner("A", null, null, null, null, NOW.minus(Duration.ofDays(1)), NOW);
        assertThat(endsNow.isLiveAt(NOW)).isFalse();
        assertThat(endsNow.statusAt(NOW)).isEqualTo(BannerStatus.EXPIRED);
        assertThat(endsNow.isLiveAt(NOW.minusMillis(1))).isTrue();
        assertThat(endsNow.statusAt(NOW.minusMillis(1))).isEqualTo(BannerStatus.LIVE);
    }

    @Test
    void khongCuaSoThiLuonHieuLucKhiActiveVaInactiveKhongBaoGio() {
        assertThat(banner("A", null, null, null, true, null, null).isLiveAt(NOW)).isTrue();
        Banner off = banner("A", null, null, null, false, null, null);
        assertThat(off.isLiveAt(NOW)).isFalse();
        assertThat(off.statusAt(NOW)).isEqualTo(BannerStatus.INACTIVE);
    }

    @Test
    void replaceWithThayToanBoGiuIdVersionVaNguoiTao() {
        UUID creator = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        Banner b = Banner.create(UUID.randomUUID(), T, "A", "s", "/x", 3, true, null, null, creator, NOW);
        Instant later = NOW.plusSeconds(60);
        Banner r = b.replaceWith("B", null, null, null, false, null, null, editor, later);
        assertThat(r.id()).isEqualTo(b.id());
        assertThat(r.version()).isEqualTo(b.version());
        assertThat(r.createdAt()).isEqualTo(NOW);
        assertThat(r.createdBy()).isEqualTo(creator);
        assertThat(r.updatedBy()).isEqualTo(editor);
        assertThat(r.updatedAt()).isEqualTo(later);
        assertThat(r.title()).isEqualTo("B");
        assertThat(r.subtitle()).isNull();
        assertThat(r.linkPath()).isNull();
        assertThat(r.sortOrder()).isZero();
        assertThat(r.active()).isFalse();
    }

    @Test
    void gioiHan20MoiTenant() {
        assertThat(BannerLimits.canCreate(19)).isTrue();
        assertThat(BannerLimits.canCreate(20)).isFalse();
        assertThat(BannerLimits.MAX_PUBLIC).isEqualTo(10);
    }
}
