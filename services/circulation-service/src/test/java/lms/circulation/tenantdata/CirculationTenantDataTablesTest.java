package lms.circulation.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import lms.circulation.tenantdata.application.CirculationTenantDataTables;
import lms.tenant.export.ExportTable;
import org.junit.jupiter.api.Test;

class CirculationTenantDataTablesTest {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|secret|token|hash|salt|credential|api_key|signature).*");

    @Test
    void xuatDungTapBangCirculationCuaTenant() {
        assertThat(CirculationTenantDataTables.TABLES).extracting(ExportTable::name).containsExactlyInAnyOrder(
                "borrowings", "borrowing_items", "reservations", "fines", "fine_adjustment_requests", "fine_refunds",
                "circulation_policies", "compensation_policies", "damage_levels", "borrow_rules", "loan_reminders");
    }

    @Test
    void khongCotNhayCamVaKhongXuatTenantId() {
        for (ExportTable t : CirculationTenantDataTables.TABLES) {
            assertThat(t.columns()).as(t.name()).noneMatch(c -> SENSITIVE.matcher(c).matches())
                    .doesNotContain("tenant_id");
            assertThat(t.columns()).as(t.name()).containsAll(t.keyColumns());
        }
    }

    @Test
    void bangKhongCoKhoaDuyNhatDocTheoNhomKhoa() {
        ExportTable items = CirculationTenantDataTables.TABLES.stream()
                .filter(t -> t.name().equals("borrowing_items")).findFirst().orElseThrow();
        assertThat(items.uniqueKey()).isFalse();
        assertThat(CirculationTenantDataTables.TABLES.stream().filter(t -> !t.uniqueKey()).count()).isEqualTo(1);
    }

    @Test
    void danhBaTenantNoiBoBiLoaiCoLyDo() {
        assertThat(CirculationTenantDataTables.EXCLUDED).extracting(e -> e.target()).containsExactly("known_tenants");
    }
}
