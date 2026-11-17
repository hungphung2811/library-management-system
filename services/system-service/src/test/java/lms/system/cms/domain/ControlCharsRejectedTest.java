package lms.system.cms.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** BUG-2: ký tự điều khiển (NUL...) bị chặn ở domain, thông điệp không lộ giá trị. */
class ControlCharsRejectedTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static CmsException submit(String name, String email, String phone, String org, String msg) {
        return (CmsException) assertThatThrownBy(() -> ContactRequest.submit(UUID.randomUUID(), name, email, phone,
                org, BusinessType.LIBRARY, msg, true, NOW)).isInstanceOf(CmsException.class).actual();
    }

    private static void assertInvalid(CmsException ex) {
        assertThat(ex.code()).isEqualTo(CmsErrorCode.CONTACT_REQUEST_INVALID);
        assertThat(ex.getMessage()).doesNotContain("\u0000", "\u001b", "x\u0000y");
    }

    @Test
    void tungTruongChuaNulBiTuChoi() {
        assertInvalid(submit("x\u0000y", "a@example.com", "090", "Org", "m"));
        assertInvalid(submit("n", "a\u0000@example.com", "090", "Org", "m"));
        assertInvalid(submit("n", "a@example.com", "09\u00000", "Org", "m"));
        assertInvalid(submit("n", "a@example.com", "090", "O\u0000rg", "m"));
        assertInvalid(submit("n", "a@example.com", "090", "Org", "m\u0000m"));
    }

    @Test
    void kyTuDieuKhienKhacBiTuChoiOTruongMotDong() {
        assertInvalid(submit("x\u001by", "a@example.com", "090", "Org", "m"));
        assertInvalid(submit("x\ny", "a@example.com", "090", "Org", "m"));
        assertInvalid(submit("n", "a@example.com", "09\t0", "Org", "m"));
        assertInvalid(submit("n", "a@example.com", "090", "Org", "m\u001bm"));
    }

    @Test
    void loiNhanChoPhepXuongDongVaTab() {
        ContactRequest r = ContactRequest.submit(UUID.randomUUID(), "n", "a@example.com", "090", "Org",
                BusinessType.LIBRARY, "dòng 1\r\ndòng 2\tcột", true, NOW);

        assertThat(r.message()).contains("\n", "\t");
    }

    @Test
    void trangQuanTriChanNulOTieuDeVaNoiDung() {
        for (String[] tb : new String[][] {{"t\u0000", "b"}, {"t", "b\u0000b"}, {"t\u001b", "b"}}) {
            assertThatThrownBy(() -> CmsPage.create(UUID.randomUUID(), "slug", tb[0], tb[1], 0, UUID.randomUUID(), NOW))
                    .isInstanceOfSatisfying(CmsException.class,
                            ex -> assertThat(ex.code()).isEqualTo(CmsErrorCode.CMS_PAGE_INVALID));
        }
        CmsPage page = CmsPage.create(UUID.randomUUID(), "slug", "t", "dòng 1\n\tdòng 2", 0, UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> page.edit("t", "x\u0000", 0, UUID.randomUUID(), NOW))
                .isInstanceOf(CmsException.class);
    }
}
