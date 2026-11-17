package lms.circulation.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.config.SecurityConfiguration;
import lms.circulation.reporting.application.CopyLabelLookupPort;
import lms.circulation.reporting.application.ExportOverdueBorrowingsCsvUseCase;
import lms.circulation.reporting.application.GetBorrowingTrendUseCase;
import lms.circulation.reporting.application.GetLendingSummaryUseCase;
import lms.circulation.reporting.application.GetTopBorrowedTitlesUseCase;
import lms.circulation.reporting.application.LendingStatsPort;
import lms.circulation.reporting.application.ReaderNameLookupPort;
import lms.circulation.reporting.domain.DateRange;
import lms.circulation.reporting.domain.LendingCounts;
import lms.circulation.reporting.domain.OverdueCopy;
import lms.circulation.reporting.domain.TopTitle;
import lms.circulation.reporting.domain.UnpaidFines;
import lms.circulation.reporting.infrastructure.web.LendingReportController;
import lms.circulation.reporting.infrastructure.web.LendingStatsController;
import lms.circulation.reporting.infrastructure.web.ReportingExceptionHandler;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ma trận quyền/module và hợp đồng HTTP của thống kê và báo cáo mượn trả (ADR-0018). Use case thật chạy trên cổng đọc giả,
 * nên kiểm cả tiêu đề CSV, BOM, trần dòng và mã lỗi tham số. Không cần Docker.
 */
@WebMvcTest({LendingStatsController.class, LendingReportController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, ReportingExceptionHandler.class,
        LendingReportingControllerTest.UseCases.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class LendingReportingControllerTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T17:10:00Z"), VN); // 00:10 ngày 2026-03-02 giờ VN
    static final LocalDate TODAY = LocalDate.of(2026, 3, 2);
    static final UUID TENANT = UUID.randomUUID();
    static final int MAX_ROWS = 3;

    static final List<OverdueCopy> ROWS = new ArrayList<>();
    static LocalDate summaryToday;

    static final LendingStatsPort STATS = new LendingStatsPort() {
        public LendingCounts counts(LocalDate today) { summaryToday = today; return new LendingCounts(7, 3, 5, 2, 1); }
        public UnpaidFines unpaidFines() { return new UnpaidFines(4, new BigDecimal("125000.00")); }
        public Map<LocalDate, Long> borrowedPerDay(DateRange range) { return Map.of(TODAY, 2L); }
        public Map<LocalDate, Long> returnedPerDay(DateRange range) { return Map.of(); }
        public List<TopTitle> topBorrowedTitles(DateRange range, int limit) {
            return List.of(new TopTitle(UUID.fromString("00000000-0000-0000-0000-000000000001"), 9));
        }
        public long countOverdueCopies(LocalDate today) { return ROWS.size(); }
        public List<OverdueCopy> overdueCopies(LocalDate today, int offset, int limit) {
            return ROWS.subList(Math.min(offset, ROWS.size()), Math.min(offset + limit, ROWS.size()));
        }
    };

    @TestConfiguration
    static class UseCases {
        @Bean GetLendingSummaryUseCase summary() { return new GetLendingSummaryUseCase(STATS, CLOCK); }
        @Bean GetBorrowingTrendUseCase trend() { return new GetBorrowingTrendUseCase(STATS, CLOCK); }
        @Bean GetTopBorrowedTitlesUseCase top() { return new GetTopBorrowedTitlesUseCase(STATS, CLOCK); }
        @Bean ExportOverdueBorrowingsCsvUseCase export() {
            ReaderNameLookupPort readers = ids -> Map.of();
            CopyLabelLookupPort copies = ids -> Map.of();
            return new ExportOverdueBorrowingsCsvUseCase(STATS, readers, copies, CLOCK, MAX_ROWS);
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @BeforeEach
    void reset() {
        ROWS.clear();
        summaryToday = null;
    }

    private static JwtRequestPostProcessor token(String... keys) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                        .claim("permissions", List.of(keys)))
                .authorities(java.util.Arrays.stream(keys).map(SimpleGrantedAuthority::new)
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString()).claim("admin", true));
    }

    private void addRows(int n) {
        for (int i = 0; i < n; i++) {
            ROWS.add(new OverdueCopy(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    TODAY.minusDays(20), TODAY.minusDays(5L + i)));
        }
    }

    // ---- quyền ----

    @Test
    void khongTokenBi401CaBaEndpointThongKeVaBaoCao() throws Exception {
        for (String path : List.of("/summary", "/borrowing-trend", "/top-titles", "/overdue-borrowings.csv")) {
            mvc.perform(get("/internal/reporting/lending" + path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void docGiaKhongCoKhoaBi403() throws Exception {
        for (String path : List.of("/summary", "/borrowing-trend", "/top-titles", "/overdue-borrowings.csv")) {
            mvc.perform(get("/internal/reporting/lending" + path).with(token()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void moiKhoaChiMoDungEndpointCuaNo() throws Exception {
        mvc.perform(get("/internal/reporting/lending/summary").with(token("LendingStatsController/summary")))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/reporting/lending/borrowing-trend").with(token("LendingStatsController/summary")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/reporting/lending/borrowing-trend").with(token("LendingStatsController/borrowingTrend")))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/reporting/lending/top-titles").with(token("LendingStatsController/topBorrowedTitles")))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/reporting/lending/top-titles").with(token("LendingStatsController/borrowingTrend")))
                .andExpect(status().isForbidden());
    }

    @Test
    void khoaXemDashboardKhongMoDuocBaoCaoXuat() throws Exception {
        mvc.perform(get("/internal/reporting/lending/overdue-borrowings.csv")
                        .with(token("LendingStatsController/summary", "LendingStatsController/borrowingTrend",
                                "LendingStatsController/topBorrowedTitles")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/reporting/lending/overdue-borrowings.csv")
                        .with(token("LendingReportController/overdueBorrowingsCsv")))
                .andExpect(status().isOk());
    }

    @Test
    void tenantAdminQuaMoiKhoa() throws Exception {
        for (String path : List.of("/summary", "/borrowing-trend", "/top-titles", "/overdue-borrowings.csv")) {
            mvc.perform(get("/internal/reporting/lending" + path).with(admin())).andExpect(status().isOk());
        }
    }

    @Test
    void tokenKhongCoTenantBi403TenantRequiredKeCaAdmin() throws Exception {
        mvc.perform(get("/internal/reporting/lending/summary").with(jwt().jwt(j -> j.claim("admin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void tokenThieuModuleLendingBi403ModuleNotEnabledKeCaAdmin() throws Exception {
        for (String path : List.of("/summary", "/overdue-borrowings.csv")) {
            mvc.perform(get("/internal/reporting/lending" + path)
                            .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                                    .claim("modules", List.of("SALES")).claim("admin", true))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }
    }

    // ---- JSON ----

    @Test
    void summaryTraDungHopDongVaHomNayGioVn() throws Exception {
        mvc.perform(get("/internal/reporting/lending/summary").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-03-02"))
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.lending.activeBorrowings").value(7))
                .andExpect(jsonPath("$.lending.overdueBorrowings").value(3))
                .andExpect(jsonPath("$.lending.overdueCopies").value(5))
                .andExpect(jsonPath("$.lending.borrowedToday").value(2))
                .andExpect(jsonPath("$.lending.returnedToday").value(1))
                .andExpect(jsonPath("$.fines.unpaidCount").value(4))
                .andExpect(jsonPath("$.fines.unpaidAmount").value(125000.00));
        assertThat(summaryToday).isEqualTo(TODAY);
    }

    @Test
    void summaryNhanAsOf() throws Exception {
        mvc.perform(get("/internal/reporting/lending/summary").param("asOf", "2026-01-15").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-01-15"));
        assertThat(summaryToday).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void asOfSaiDinhDangBi400() throws Exception {
        mvc.perform(get("/internal/reporting/lending/summary").param("asOf", "15/01/2026").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void borrowingTrendNhanBaThamSoTrongMotRecordVaTraDuMoc() throws Exception {
        mvc.perform(get("/internal/reporting/lending/borrowing-trend")
                        .param("from", "2026-02-28").param("to", "2026-03-02").param("granularity", "day").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-02-28"))
                .andExpect(jsonPath("$.to").value("2026-03-02"))
                .andExpect(jsonPath("$.granularity").value("day"))
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].bucket").value("2026-02-28"))
                .andExpect(jsonPath("$.points[0].borrowed").value(0))
                .andExpect(jsonPath("$.points[2].bucket").value("2026-03-02"))
                .andExpect(jsonPath("$.points[2].borrowed").value(2));
    }

    @Test
    void borrowingTrendMacDinh30Ngay() throws Exception {
        mvc.perform(get("/internal/reporting/lending/borrowing-trend").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("2026-03-02"))
                .andExpect(jsonPath("$.points.length()").value(30));
    }

    @Test
    void borrowingTrendLoiThamSoTraMaLoi400() throws Exception {
        mvc.perform(get("/internal/reporting/lending/borrowing-trend").param("granularity", "month").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GRANULARITY"));
        mvc.perform(get("/internal/reporting/lending/borrowing-trend")
                        .param("from", "2026-03-02").param("to", "2026-03-01").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get("/internal/reporting/lending/borrowing-trend")
                        .param("from", "2025-01-01").param("to", "2026-03-01").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
        mvc.perform(get("/internal/reporting/lending/borrowing-trend").param("from", "khong-phai-ngay").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topTitlesTraItemsVaKiemLimit() throws Exception {
        mvc.perform(get("/internal/reporting/lending/top-titles").param("limit", "5").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.items[0].titleId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.items[0].count").value(9));
        for (String bad : List.of("0", "51")) {
            mvc.perform(get("/internal/reporting/lending/top-titles").param("limit", bad).with(admin()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        }
        mvc.perform(get("/internal/reporting/lending/top-titles").param("limit", "abc").with(admin()))
                .andExpect(status().isBadRequest());
    }

    // ---- CSV ----

    @Test
    void csvCoDauHieuHttpDungBomVaTieuDe() throws Exception {
        addRows(2);

        byte[] body = mvc.perform(get("/internal/reporting/lending/overdue-borrowings.csv").with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"overdue-borrowings-20260302.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("X-Report-Truncated"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body[0]).isEqualTo((byte) 0xEF);
        assertThat(body[1]).isEqualTo((byte) 0xBB);
        assertThat(body[2]).isEqualTo((byte) 0xBF);
        String[] lines = new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines[0]).isEqualTo("borrowingId,userId,readerName,titleName,barcode,borrowDate,dueDate,daysOverdue");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).endsWith(",5");  // dueDate = hôm nay - 5
        assertThat(lines[2]).endsWith(",6");
    }

    @Test
    void csvVuotTranCatBotVaDatHeaderTruncated() throws Exception {
        addRows(MAX_ROWS + 2);

        byte[] body = mvc.perform(get("/internal/reporting/lending/overdue-borrowings.csv").with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Report-Truncated", "true"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8).split("\r\n"))
                .hasSize(1 + MAX_ROWS);
    }

    @Test
    void csvDungBangTranKhongCoHeaderTruncated() throws Exception {
        addRows(MAX_ROWS);

        mvc.perform(get("/internal/reporting/lending/overdue-borrowings.csv").with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Report-Truncated"));
    }
}
