package lms.identity.auth.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.auth.application.ListMySessionsUseCase;
import lms.identity.auth.application.RevokeMySessionUseCase;
import lms.identity.auth.application.RevokeMySessionsUseCase;
import lms.identity.auth.application.SessionView;
import lms.identity.auth.domain.SessionNotFoundException;
import lms.identity.auth.domain.SessionNotIdentifiedException;
import lms.identity.auth.infrastructure.web.SessionController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * API phiên của tôi (ADR-0017). Bật lại kiểm khóa: endpoint {@code @AuthenticatedOnly} không cần khóa nào, không đăng ký
 * khóa nào; token dịch vụ (svc) bị từ chối.
 */
@WebMvcTest(SessionController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class SessionControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean ListMySessionsUseCase list;
    @MockitoBean RevokeMySessionUseCase revokeOne;
    @MockitoBean RevokeMySessionsUseCase revokeMany;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SID = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    /** Token không quyền nào, không tenantId (như platform admin) và có sid. */
    private static RequestPostProcessor plain() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("sid", SID.toString()));
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/auth/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/internal/auth/sessions/" + OTHER)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/sessions/revocation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"keepCurrent\":true}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(list, revokeOne, revokeMany);
    }

    @Test
    void tokenKhongTenantKhongQuyenVanListDuocVaTruyenSidHienTai() throws Exception {
        Instant t = Instant.parse("2026-11-17T01:00:00Z");
        when(list.execute(USER, SID)).thenReturn(List.of(new SessionView(SID, "STAFF", null, null,
                "Chrome trên Windows", "113.161.45.9", t, t, t.plusSeconds(3600), true)));

        mvc.perform(get("/internal/auth/sessions").with(plain()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$[0].sessionId").value(SID.toString()))
                .andExpect(jsonPath("$[0].ipAddress").value("113.161.45.9"))
                .andExpect(jsonPath("$[0].current").value(true));
        // @AuthenticatedOnly: không khóa nào được đăng ký
        verifyNoInteractions(permissionKeyRegistrar);
    }

    @Test
    void tokenPhatTruocAdr0017KhongCoSidThiCurrentLaNull() throws Exception {
        when(list.execute(eq(USER), org.mockito.ArgumentMatchers.isNull())).thenReturn(List.of());
        mvc.perform(get("/internal/auth/sessions")
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isOk());
        verify(list).execute(eq(USER), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void tokenMustChangePasswordVanDungDuoc() throws Exception {
        when(list.execute(any(), any())).thenReturn(List.of());
        mvc.perform(get("/internal/auth/sessions")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("mustChangePassword", true))))
                .andExpect(status().isOk());
    }

    @Test
    void tokenDichVuSvcBi403() throws Exception {
        RequestPostProcessor svc = jwt().jwt(j -> j.subject(USER.toString()).claim("svc", "customer-api")
                .claim("tenantId", UUID.randomUUID().toString()));
        mvc.perform(get("/internal/auth/sessions").with(svc)).andExpect(status().isForbidden());
        mvc.perform(delete("/internal/auth/sessions/" + OTHER).with(svc)).andExpect(status().isForbidden());
        mvc.perform(post("/internal/auth/sessions/revocation").with(svc).contentType(MediaType.APPLICATION_JSON)
                .content("{\"keepCurrent\":false}")).andExpect(status().isForbidden());
        verifyNoInteractions(list, revokeOne, revokeMany);
    }

    @Test
    void xoaPhienKhongCoHoacCuaNguoiKhacTra404MaChung() throws Exception {
        doThrow(new SessionNotFoundException()).when(revokeOne).execute(USER, OTHER);
        mvc.perform(delete("/internal/auth/sessions/" + OTHER).with(plain()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void xoaPhienCuaMinhTra204() throws Exception {
        mvc.perform(delete("/internal/auth/sessions/" + SID).with(plain())).andExpect(status().isNoContent());
        verify(revokeOne).execute(USER, SID);
    }

    @Test
    void thuHoiHangLoatKeepCurrentThieuSidTra409() throws Exception {
        when(revokeMany.execute(eq(USER), org.mockito.ArgumentMatchers.isNull(), eq(true)))
                .thenThrow(new SessionNotIdentifiedException());
        mvc.perform(post("/internal/auth/sessions/revocation")
                        .with(jwt().jwt(j -> j.subject(USER.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keepCurrent\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_IDENTIFIED"));
    }

    @Test
    void thuHoiHangLoatTraSoPhien() throws Exception {
        when(revokeMany.execute(USER, SID, true)).thenReturn(3);
        mvc.perform(post("/internal/auth/sessions/revocation").with(plain())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keepCurrent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessions").value(3));
    }
}
