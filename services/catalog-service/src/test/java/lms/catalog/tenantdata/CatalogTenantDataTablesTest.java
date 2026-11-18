package lms.catalog.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import lms.catalog.tenantdata.application.CatalogTenantDataTables;
import lms.tenant.export.ExportTable;
import org.junit.jupiter.api.Test;

class CatalogTenantDataTablesTest {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|secret|token|hash|salt|credential|api_key|signature).*");

    @Test
    void xuatDungTapBangCatalogCuaTenant() {
        assertThat(CatalogTenantDataTables.TABLES).extracting(ExportTable::name).containsExactlyInAnyOrder(
                "titles", "authors", "publishers", "categories", "shelf_locations", "series", "works",
                "work_authors", "title_authors", "book_copies");
    }

    @Test
    void khongCotNhayCamVaKhongXuatTenantId() {
        for (ExportTable t : CatalogTenantDataTables.TABLES) {
            assertThat(t.columns()).as(t.name()).noneMatch(c -> SENSITIVE.matcher(c).matches())
                    .doesNotContain("tenant_id");
            assertThat(t.columns()).as(t.name()).containsAll(t.keyColumns());
        }
    }

    @Test
    void bangTieuDeCoDuCotMoRong() {
        ExportTable titles = CatalogTenantDataTables.TABLES.stream().filter(t -> t.name().equals("titles"))
                .findFirst().orElseThrow();
        assertThat(titles.columns()).contains("isbn", "cover_price", "tags", "work_id", "publisher_id", "category_id");
    }
}
