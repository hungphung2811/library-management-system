package lms.support.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import lms.support.tenantdata.application.SupportTenantDataTables;
import lms.tenant.export.ExportTable;
import org.junit.jupiter.api.Test;

class SupportTenantDataTablesTest {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|secret|token|hash|salt|credential|api_key|signature).*");

    private static ExportTable table(String name) {
        return SupportTenantDataTables.TABLES.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void xuatDungTapBangSupportCuaTenant() {
        assertThat(SupportTenantDataTables.TABLES).extracting(ExportTable::name).containsExactlyInAnyOrder(
                "notifications", "notification_deliveries", "notification_templates", "notification_channel_settings",
                "notification_preferences", "audit_logs");
    }

    @Test
    void khongCotNhayCamVaKhongXuatTenantId() {
        for (ExportTable t : SupportTenantDataTables.TABLES) {
            assertThat(t.columns()).as(t.name()).noneMatch(c -> SENSITIVE.matcher(c).matches())
                    .doesNotContain("tenant_id");
            assertThat(t.columns()).as(t.name()).containsAll(t.keyColumns());
        }
    }

    @Test
    void deliveriesKhongXuatDiaChiDayDuVaKhoaKhuTrung() {
        ExportTable deliveries = table("notification_deliveries");
        assertThat(deliveries.columns()).contains("recipient_address_masked")
                .doesNotContain("recipient_address", "recipient_key");
        assertThat(deliveries.excludedColumns()).extracting(e -> e.target()).containsExactly(
                "notification_deliveries.recipient_address", "notification_deliveries.recipient_key");
    }

    @Test
    void nhatKyAuditChiaFileLonVaBanThoMongoBiLoaiCoLyDo() {
        assertThat(table("audit_logs").splitEveryRows()).isEqualTo(SupportTenantDataTables.AUDIT_ROWS_PER_FILE)
                .isPositive();
        assertThat(SupportTenantDataTables.EXCLUDED).extracting(e -> e.target())
                .containsExactlyInAnyOrder("notification_tenants", "mongo.raw_events");
    }
}
