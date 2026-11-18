package lms.identity.tenantdata.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Danh sách bảng/cột xuất của identity là CỐ ĐỊNH và không chứa trường nhạy cảm (ADR-0031 D2): mật khẩu băm, refresh token,
 * secret credential Application, site key, ghi chú nội bộ, khóa nội bộ. Mọi truy vấn lọc tenant tường minh.
 */
class IdentityTenantDataSourceTest {

    private static final List<String> FORBIDDEN_COLUMNS = List.of("password", "secret", "hash", "refresh", "token",
            "site_key", "internal_note", "api_key", "otp", "salt");
    private static final List<String> FORBIDDEN_TABLES = List.of("credentials", "refresh_tokens",
            "password_reset_tokens", "email_verifications", "external_sign_in_tickets", "external_logins",
            "application_credentials", "permissions");

    @Test
    void khongBangHayCotNhayCamNaoTrongTruyVanXuat() {
        for (IdentityTenantDataSource.Table t : IdentityTenantDataSource.TABLES) {
            String sql = t.sql().toLowerCase();
            for (String bad : FORBIDDEN_COLUMNS) {
                assertThat(sql).as(t.name() + " không được chứa " + bad).doesNotContain(bad);
            }
            for (String bad : FORBIDDEN_TABLES) {
                assertThat(Pattern.compile("\\b(from|join)\\s+" + bad + "\\b").matcher(sql).find())
                        .as(t.name() + " không được đọc bảng " + bad).isFalse();
            }
        }
    }

    @Test
    void moiTruyVanLocTenantTuongMinhVaCoThuTuOnDinh() {
        for (IdentityTenantDataSource.Table t : IdentityTenantDataSource.TABLES) {
            assertThat(t.sql()).as(t.name()).containsPattern("tenant_id = :t");
            assertThat(t.sql()).as(t.name()).contains("ORDER BY");
        }
    }

    @Test
    void bangBiLoaiDuocLietKeVoiLyDoDeManifestMinhBach() {
        assertThat(IdentityTenantDataSource.EXCLUDED).containsKeys("credentials", "refresh_tokens",
                "password_reset_tokens", "application_credentials", "applications.site_key",
                "tenant_contracts.internal_note");
        assertThat(IdentityTenantDataSource.EXCLUDED.values()).allMatch(reason -> !reason.isBlank());
    }

    @Test
    void danhSachBangDayDuChoDuLieuTenant() {
        assertThat(IdentityTenantDataSource.TABLES.stream().map(IdentityTenantDataSource.Table::name).toList())
                .containsExactly("users", "tenant_memberships", "roles", "role_permissions", "user_roles",
                        "departments", "reader_profiles", "applications", "tenant_contracts");
    }

    @Test
    void dinhDangThoiGianIso8601Utc() throws Exception {
        assertThat(IdentityTenantDataSource.format(Timestamp.from(Instant.parse("2026-11-19T01:02:03Z"))))
                .isEqualTo("2026-11-19T01:02:03Z");
        assertThat(IdentityTenantDataSource.format(null)).isNull();
        assertThat(IdentityTenantDataSource.format(true)).isEqualTo("true");
    }

    @Test
    void chongCongThucBangTinhChiChoODanVanBanCungQuyUocVoiExporterKhac() throws Exception {
        assertThat(IdentityTenantDataSource.format("=HYPERLINK(\"http://x\")")).isEqualTo("'=HYPERLINK(\"http://x\")");
        assertThat(IdentityTenantDataSource.format("+84 912")).isEqualTo("'+84 912");
        assertThat(IdentityTenantDataSource.format("@a")).isEqualTo("'@a");
        assertThat(IdentityTenantDataSource.format("Nguyễn Văn A")).isEqualTo("Nguyễn Văn A");
        assertThat(IdentityTenantDataSource.format(-5)).isEqualTo("-5");
        assertThat(IdentityTenantDataSource.format("")).isEmpty();
    }
}
