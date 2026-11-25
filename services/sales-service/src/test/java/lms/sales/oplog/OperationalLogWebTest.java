package lms.sales.oplog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.oplog.application.OperationalLogQueryUseCase;
import lms.sales.oplog.application.OperationalLogQueryUseCase.Filter;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.sales.oplog.infrastructure.web.OperationalLogController;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.HarnessBeans;
import lms.sales.support.WebTokens;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.TenantScopeBinder;
import lms.tenant.TenantContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Phân quyền động (kiểm khóa bật như production), module SALES, lọc/giới hạn và cách ly tenant của log vận hành. */
@WebMvcTest(OperationalLogController.class)
@Import({OperationalLogWebTest.Binder.class, SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class OperationalLogWebTest {

    /** Gắn phạm vi tenant cho @CrossTenantReadable như production (import tay không bảo đảm thứ tự auto-config). */
    @TestConfiguration
    static class Binder {
        @Bean
        TenantScopeBinder tenantScopeBinder() {
            return new TenantScopeBinder() {
                @Override
                public void bindAll() {
                    TenantContext.bindAllTenants();
                }

                @Override
                public void bindSingle(UUID tenantId) {
                    TenantContext.set(tenantId);
                }
            };
        }
    }

    private static final String INTEGRATION = "/internal/sales/logs/integration";
    private static final String SYSTEM = "/internal/sales/logs/system";
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean OperationalLogQueryUseCase useCase;

    @BeforeEach
    void stub() {
        OperationalLogEvent e = new OperationalLogEvent(UUID.randomUUID(), TENANT, Instant.parse("2026-11-19T01:00:00Z"),
                OperationalLogEvent.OUTBOUND, "momo", "integration-call", "WARN", "create", "FAILURE", 502, 830L,
                "SERVER_ERROR", "SocketTimeoutException", "X", "cid-1", null, "ORD*****1234", java.util.Map.of());
        PageResult<OperationalLogEvent> page = new PageResult<>(List.of(e), 1, 0, 20);
        when(useCase.integration(any(), anyInt(), anyInt())).thenReturn(page);
        when(useCase.system(any(), anyInt(), anyInt())).thenReturn(page);
    }

    private static RequestPostProcessor user(UUID tenant, String... keys) {
        return WebTokens.withKeys(tenant, List.of("SALES"), keys);
    }

    @Test
    void coKhoaThiXemDuocVaThanPhanHoiDungHopDongKhongLoExtra() throws Exception {
        mvc.perform(get(INTEGRATION).with(user(TENANT, "OperationalLogController/integration")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].provider").value("momo"))
                .andExpect(jsonPath("$.items[0].httpStatus").value(502))
                .andExpect(jsonPath("$.items[0].reference").value("ORD*****1234"))
                .andExpect(jsonPath("$.items[0].tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.items[0].extra").doesNotExist());
        mvc.perform(get(SYSTEM).with(user(TENANT, "OperationalLogController/system"))).andExpect(status().isOk());
    }

    @Test
    void khoaTachRiengHaiTab() throws Exception {
        mvc.perform(get(SYSTEM).with(user(TENANT, "OperationalLogController/integration")))
                .andExpect(status().isForbidden());
        mvc.perform(get(INTEGRATION).with(user(TENANT, "OperationalLogController/system")))
                .andExpect(status().isForbidden());
        mvc.perform(get(INTEGRATION).with(user(TENANT))).andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }

    @Test
    void adminTenantBoQuaKhoaNhungKhongBoQuaModuleVaKhongCoTokenLa401() throws Exception {
        mvc.perform(get(INTEGRATION).with(jwt().jwt(j -> j.claim("admin", true).claim("tenantId", TENANT.toString())
                .claim("modules", List.of("SALES"))))).andExpect(status().isOk());
        mvc.perform(get(INTEGRATION).with(jwt().jwt(j -> j.claim("admin", true).claim("tenantId", TENANT.toString())
                .claim("modules", List.of("LIBRARY"))))).andExpect(status().isForbidden());
        mvc.perform(get(INTEGRATION)).andExpect(status().isUnauthorized());
    }

    @Test
    void tenantChiXemLogCuaMinhQueryTenantIdCuaTenantKhacBiBoQua() throws Exception {
        mvc.perform(get(INTEGRATION).param("tenantId", OTHER.toString())
                        .with(user(TENANT, "OperationalLogController/integration")).header("X-Tenant-Id", OTHER.toString()))
                .andExpect(status().isOk());
        ArgumentCaptor<Filter> f = ArgumentCaptor.forClass(Filter.class);
        verify(useCase).integration(f.capture(), anyInt(), anyInt());
        assertThat(f.getValue().tenantId()).isEqualTo(TENANT);
    }

    @Test
    void platformAdminCoHeaderXemTenantDichKhongHeaderXemMoiTenantVaLocDuocTheoTenantId() throws Exception {
        RequestPostProcessor platform = jwt().jwt(j -> j.claim("platformAdmin", true));
        mvc.perform(get(INTEGRATION).with(platform).header("X-Tenant-Id", OTHER.toString())).andExpect(status().isOk());
        mvc.perform(get(SYSTEM).with(platform)).andExpect(status().isOk());
        mvc.perform(get(SYSTEM).param("tenantId", OTHER.toString()).with(platform)).andExpect(status().isOk());
        ArgumentCaptor<Filter> byHeader = ArgumentCaptor.forClass(Filter.class);
        verify(useCase).integration(byHeader.capture(), anyInt(), anyInt());
        assertThat(byHeader.getValue().tenantId()).isEqualTo(OTHER);
        ArgumentCaptor<Filter> sys = ArgumentCaptor.forClass(Filter.class);
        verify(useCase, org.mockito.Mockito.times(2)).system(sys.capture(), anyInt(), anyInt());
        assertThat(sys.getAllValues().get(0).tenantId()).isNull();
        assertThat(sys.getAllValues().get(1).tenantId()).isEqualTo(OTHER);
    }

    @Test
    void locVaPhanTrangChuyenXuongUseCaseVaSizeBiChan100() throws Exception {
        mvc.perform(get(INTEGRATION).param("channel", "OUTBOUND").param("provider", "momo").param("outcome", "FAILURE")
                        .param("level", "WARN").param("anomalyOnly", "true").param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-10T00:00:00Z").param("page", "2").param("size", "9999")
                        .with(user(TENANT, "OperationalLogController/integration")))
                .andExpect(status().isOk());
        ArgumentCaptor<Filter> f = ArgumentCaptor.forClass(Filter.class);
        verify(useCase).integration(f.capture(), eq(2), eq(100));
        Filter v = f.getValue();
        assertThat(v.channel()).isEqualTo("OUTBOUND");
        assertThat(v.provider()).isEqualTo("momo");
        assertThat(v.outcome()).isEqualTo("FAILURE");
        assertThat(v.level()).isEqualTo("WARN");
        assertThat(v.anomalyOnly()).isTrue();
        assertThat(v.from()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(v.to()).isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
    }

    @Test
    void khoangThoiGianVuotGioiHanLa400() throws Exception {
        when(useCase.integration(any(), anyInt(), anyInt()))
                .thenThrow(new SalesException(ErrorCode.INVALID_RANGE, "Khoảng thời gian tối đa 31 ngày"));
        mvc.perform(get(INTEGRATION).with(user(TENANT, "OperationalLogController/integration")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }
}
