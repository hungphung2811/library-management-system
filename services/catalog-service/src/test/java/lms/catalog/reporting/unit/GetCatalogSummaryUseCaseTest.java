package lms.catalog.reporting.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lms.catalog.inventory.domain.CopyStatus;
import lms.catalog.reporting.application.CatalogStatsQuery;
import lms.catalog.reporting.application.CatalogSummaryResponse;
import lms.catalog.reporting.application.GetCatalogSummaryUseCase;
import lms.catalog.reporting.domain.CopyCounts;
import org.junit.jupiter.api.Test;

class GetCatalogSummaryUseCaseTest {

    private static CatalogStatsQuery query(long titles, Map<String, Long> byStatus) {
        return new CatalogStatsQuery() {
            @Override
            public long countTitles() {
                return titles;
            }

            @Override
            public Map<String, Long> countCopiesByStatus() {
                return byStatus;
            }
        };
    }

    @Test
    void tongBangTongCacTrangThaiVaTrangThaiVangLaKhong() {
        CatalogSummaryResponse r = new GetCatalogSummaryUseCase(
                query(62, Map.of("AVAILABLE", 100L, "LOANED", 30L, "DAMAGED", 2L))).execute();

        assertEquals(62, r.titles());
        assertEquals(132, r.copies().total());
        assertEquals(100L, r.copies().byStatus().get("AVAILABLE"));
        assertEquals(30L, r.copies().byStatus().get("LOANED"));
        assertEquals(0L, r.copies().byStatus().get("RESERVED"));
        assertEquals(0L, r.copies().byStatus().get("LOST"));
        assertEquals(2L, r.copies().byStatus().get("DAMAGED"));
    }

    @Test
    void tenantRongTraToanKhong() {
        CatalogSummaryResponse r = new GetCatalogSummaryUseCase(query(0, Map.of())).execute();

        assertEquals(0, r.titles());
        assertEquals(0, r.copies().total());
        assertEquals(5, r.copies().byStatus().size());
    }

    @Test
    void trangThaiLaKhongThuocDanhSachBiBoQuaKhoiTong() {
        CopyCounts c = new CopyCounts(Map.of("AVAILABLE", 1L, "WEIRD", 9L));
        assertEquals(1, c.total());
        assertFalse(c.byStatus().containsKey("WEIRD"));
    }

    @Test
    void danhSachTrangThaiKhopEnumCuaInventory() {
        List<String> enumNames = Arrays.stream(CopyStatus.values()).map(Enum::name).toList();
        assertEquals(enumNames, CopyCounts.STATUSES);
    }
}
