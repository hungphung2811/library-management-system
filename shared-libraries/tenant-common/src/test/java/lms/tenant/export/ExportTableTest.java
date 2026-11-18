package lms.tenant.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportTableTest {

    @Test
    void tuChoiCotNhayCamTheoTen() {
        for (String col : List.of("password_hash", "refresh_token", "client_secret", "qr_payload", "Signature_x",
                "api_key", "credential_id", "salt")) {
            assertThatThrownBy(() -> ExportTable.of("t", List.of("id"), "id", col.toLowerCase()))
                    .as(col).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void tuChoiDinhDanhKhongHopLeVaTenantId() {
        assertThatThrownBy(() -> ExportTable.of("t; drop table x", List.of("id"), "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportTable.of("t", List.of("id"), "id", "a b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportTable.of("t", List.of("id"), "id", "tenant_id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cotKhoaPhaiNamTrongDanhSachCot() {
        assertThatThrownBy(() -> ExportTable.of("t", List.of("khac"), "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ExportTable.byBusinessKey("t", List.of("a"), "a").uniqueKey()).isFalse();
        assertThat(ExportTable.of("t", List.of("a"), "a").uniqueKey()).isTrue();
    }

    @Test
    void voHieuHoaCongThucChiODanVanBanKhongDungSoAm() throws Exception {
        for (String s : List.of("=SUM(A1)", "+84901234567", "-cmd", "@x", "\tx", "\rx")) {
            assertThat(JdbcTenantTableReader.formatValue(s)).as(s).isEqualTo("'" + s);
        }
        assertThat(JdbcTenantTableReader.formatValue("Nguyễn Văn A")).isEqualTo("Nguyễn Văn A");
        assertThat(JdbcTenantTableReader.formatValue("a=b")).isEqualTo("a=b");
        assertThat(JdbcTenantTableReader.formatValue("")).isEmpty();
        assertThat(JdbcTenantTableReader.formatValue(new BigDecimal("-5.50"))).isEqualTo("-5.50");
        assertThat(JdbcTenantTableReader.formatValue(-5)).isEqualTo("-5");
        assertThat(JdbcTenantTableReader.formatValue(-7L)).isEqualTo("-7");
    }

    @Test
    void dinhDangODungHopDong() throws Exception {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertThat(JdbcTenantTableReader.formatValue(null)).isNull();
        assertThat(JdbcTenantTableReader.formatValue(id)).isEqualTo(id.toString());
        assertThat(JdbcTenantTableReader.formatValue(true)).isEqualTo("true");
        assertThat(JdbcTenantTableReader.formatValue(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(JdbcTenantTableReader.formatValue(Timestamp.from(Instant.parse("2026-11-19T10:15:30Z"))))
                .isEqualTo("2026-11-19T10:15:30Z");
        assertThat(JdbcTenantTableReader.formatValue(java.sql.Date.valueOf("2026-11-19"))).isEqualTo("2026-11-19");
        assertThat(JdbcTenantTableReader.jsonArray(new Object[] {"a\"b", 3, null})).isEqualTo("[\"a\\\"b\",3,null]");
    }
}
