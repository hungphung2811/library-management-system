package lms.circulation.fine.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.circulation.config.SecurityConfiguration;
import lms.circulation.fine.application.CollectFinesUseCase;
import lms.circulation.fine.application.GetReaderFinesUseCase;
import lms.circulation.fine.infrastructure.web.FineController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** RBAC cho FineController: độc giả xem phạt của mình chỉ cần đăng nhập, thu phạt và xem phạt người khác cần quyền. */
@WebMvcTest(FineController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class FineSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean GetReaderFinesUseCase getFines;
    @MockitoBean CollectFinesUseCase collectFines;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/fines/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void docGiaXemPhatCuaMinhChiCanDangNhapVaUserIdLayTuToken() throws Exception {
        UUID userId = UUID.randomUUID();
        when(getFines.execute(userId)).thenReturn(
                new GetReaderFinesUseCase.ReaderFinesResponse(userId, new BigDecimal("15000"), List.of()));

        mvc.perform(get("/internal/fines/mine").with(jwt().jwt(j -> j.subject(userId.toString()).claim("tenantId", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUnpaid").value(15000));
    }

    @Test
    void xemPhatCuaDocGiaKhacCanQuyen() throws Exception {
        mvc.perform(get("/internal/fines").param("readerId", UUID.randomUUID().toString()).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminKhongTenantXemPhatTheoDocGiaXuyenTenant() throws Exception {
        UUID readerId = UUID.randomUUID();
        when(getFines.execute(readerId)).thenReturn(
                new GetReaderFinesUseCase.ReaderFinesResponse(readerId, new BigDecimal("15000"), List.of()));

        mvc.perform(get("/internal/fines").param("readerId", readerId.toString())
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUnpaid").value(15000));
        mvc.perform(get("/internal/fines").param("readerId", readerId.toString())
                        .param("tenantId", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminTenantIdSaiDinhDangBi400() throws Exception {
        mvc.perform(get("/internal/fines").param("readerId", UUID.randomUUID().toString())
                        .param("tenantId", "123").with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }

    @Test
    void nguoiKhacKhongTenantVanBi403TenantRequiredDuGuiTenantId() throws Exception {
        for (var token : List.of(jwt(), jwt().jwt(j -> j.claim("admin", true)))) {
            mvc.perform(get("/internal/fines").param("readerId", UUID.randomUUID().toString())
                            .param("tenantId", UUID.randomUUID().toString()).with(token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    @Test
    void platformAdminKhongTenantKhongThuPhatDuoc() throws Exception {
        mvc.perform(post("/internal/fines/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"readerId\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void thuPhatThieuQuyenBi403DuQuyenThanhCong() throws Exception {
        UUID readerId = UUID.randomUUID();
        String body = "{\"readerId\":\"" + readerId + "\"}";
        when(collectFines.execute(any())).thenReturn(
                new CollectFinesUseCase.CollectFinesResponse(readerId, 1, new BigDecimal("15000"), List.of()));

        mvc.perform(post("/internal/fines/payments").contentType(MediaType.APPLICATION_JSON).content(body).with(jwt()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/fines/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(j -> j.claim("tenantId", UUID.randomUUID().toString()).claim("permissions", List.of("FineController/collect")))
                                .authorities(new SimpleGrantedAuthority("FineController/collect"))))
                .andExpect(status().isOk());
    }
}
