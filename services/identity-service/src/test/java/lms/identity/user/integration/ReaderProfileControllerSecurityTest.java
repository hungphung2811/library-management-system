package lms.identity.user.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.config.SecurityConfiguration;
import lms.identity.user.application.ChangeReaderStatusUseCase;
import lms.identity.user.application.GetReaderProfileUseCase;
import lms.identity.user.application.IssueReaderCardCommand;
import lms.identity.user.application.IssueReaderCardUseCase;
import lms.identity.user.application.ReaderProfileResponse;
import lms.identity.user.application.RenewReaderCardUseCase;
import lms.identity.user.domain.DuplicateReaderException;
import lms.identity.user.domain.InvalidReaderException;
import lms.identity.user.domain.ReaderProfileNotFoundException;
import lms.identity.user.domain.ReaderStatus;
import lms.identity.user.domain.ReaderType;
import lms.identity.user.infrastructure.web.ReaderProfileController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(ReaderProfileController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "lms.security.permission.require-tenant=true"})
class ReaderProfileControllerSecurityTest {

    static final String TENANT = UUID.randomUUID().toString();
    static final LocalDate EXPIRY = LocalDate.of(2030, 1, 1);

    @Autowired MockMvc mvc;

    @MockitoBean IssueReaderCardUseCase issueCard;
    @MockitoBean GetReaderProfileUseCase getProfile;
    @MockitoBean RenewReaderCardUseCase renewCard;
    @MockitoBean lms.identity.user.application.UpdateReaderProfileUseCase updateProfile;
    @MockitoBean ChangeReaderStatusUseCase changeStatus;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private final UUID readerId = UUID.randomUUID();

    private ReaderProfileResponse profile(ReaderStatus status) {
        return new ReaderProfileResponse(readerId, "SV001", "Nguyễn Văn A", "a@x.vn", ReaderType.STUDENT, EXPIRY,
                status, true);
    }

    private RequestPostProcessor with(String... keys) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("permissions", List.of(keys))
                        .claim("tenantId", TENANT))
                .authorities(Arrays.stream(keys).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true).claim("tenantId", TENANT));
    }

    private String issueBody(String extra) {
        return "{\"cardNo\":\"SV001\",\"fullName\":\"Nguyễn Văn A\",\"email\":\"a@x.vn\",\"readerType\":\"STUDENT\","
                + "\"cardExpiryDate\":\"2030-01-01\"" + extra + "}";
    }

    // ---------- GET /me/reader-profile (AuthenticatedOnly) ----------

    @Test
    void hoSoCuaToiChiCanDangNhapKhongCanKhoaVaKhongCanTenant() throws Exception {
        UUID me = UUID.randomUUID();
        when(getProfile.execute(me)).thenReturn(profile(ReaderStatus.ACTIVE));

        mvc.perform(get("/internal/users/me/reader-profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/users/me/reader-profile").with(jwt().jwt(j -> j.subject(me.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNo").value("SV001"))
                .andExpect(jsonPath("$.readerType").value("STUDENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.cardValid").value(true));
    }

    @Test
    void hoSoCuaToiKhongCoHoSoDocGiaTra404() throws Exception {
        UUID me = UUID.randomUUID();
        when(getProfile.execute(me)).thenThrow(new ReaderProfileNotFoundException(me));

        mvc.perform(get("/internal/users/me/reader-profile").with(jwt().jwt(j -> j.subject(me.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
    }

    // ---------- GET /{userId}/reader-profile ----------

    @Test
    void xemHoSoTheoIdCanDungKhoaReaderProfileControllerGet() throws Exception {
        when(getProfile.execute(readerId)).thenReturn(profile(ReaderStatus.ACTIVE));
        String url = "/internal/users/" + readerId + "/reader-profile";

        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(with())).andExpect(status().isForbidden());
        mvc.perform(get(url).with(with("ReaderProfileController/issue"))).andExpect(status().isForbidden());
        mvc.perform(get(url).with(with("ReaderProfileController/get")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(readerId.toString()))
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn A"));
        mvc.perform(get(url).with(admin())).andExpect(status().isOk());
    }

    @Test
    void xemHoSoKhongTonTaiTra404VaIdSaiDinhDangTra400() throws Exception {
        when(getProfile.execute(readerId)).thenThrow(new ReaderProfileNotFoundException(readerId));

        mvc.perform(get("/internal/users/" + readerId + "/reader-profile").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
        mvc.perform(get("/internal/users/khong-phai-uuid/reader-profile").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tokenCoKhoaNhungKhongCoTenantIdBiTuChoi() throws Exception {
        mvc.perform(get("/internal/users/" + readerId + "/reader-profile")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                        .claim("permissions", List.of("ReaderProfileController/get")))
                                .authorities(new SimpleGrantedAuthority("ReaderProfileController/get"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(getProfile);
    }

    // ---------- POST /{userId}/reader-profile ----------

    @Test
    void capTheDocGiaCanKhoaIssueTra201KemLocation() throws Exception {
        when(issueCard.execute(any())).thenReturn(profile(ReaderStatus.ACTIVE));
        String url = "/internal/users/" + readerId + "/reader-profile";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(issueBody("")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(with("ReaderProfileController/get")).contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(""))).andExpect(status().isForbidden());
        verifyNoInteractions(issueCard);

        mvc.perform(post(url).with(with("ReaderProfileController/issue")).contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(",\"username\":\"sv001\",\"temporaryPassword\":\"Matkhau@123\"")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", url))
                .andExpect(jsonPath("$.cardNo").value("SV001"));

        ArgumentCaptor<IssueReaderCardCommand> cmd = ArgumentCaptor.forClass(IssueReaderCardCommand.class);
        verify(issueCard).execute(cmd.capture());
        org.junit.jupiter.api.Assertions.assertEquals(readerId, cmd.getValue().userId());
        org.junit.jupiter.api.Assertions.assertEquals("sv001", cmd.getValue().username());
        org.junit.jupiter.api.Assertions.assertEquals(ReaderType.STUDENT, cmd.getValue().readerType());
        org.junit.jupiter.api.Assertions.assertEquals(EXPIRY, cmd.getValue().cardExpiryDate());
    }

    @Test
    void capTheDocGiaDuLieuSaiTra400() throws Exception {
        String url = "/internal/users/" + readerId + "/reader-profile";
        String longNo = "x".repeat(33);
        String[] bad = {
                "{}",
                issueBody("").replace("\"cardNo\":\"SV001\"", "\"cardNo\":\"\""),
                issueBody("").replace("\"cardNo\":\"SV001\"", "\"cardNo\":\"" + longNo + "\""),
                issueBody("").replace("\"email\":\"a@x.vn\"", "\"email\":\"khong-phai-email\""),
                issueBody("").replace("\"fullName\":\"Nguyễn Văn A\"", "\"fullName\":\"  \""),
                issueBody("").replace(",\"cardExpiryDate\":\"2030-01-01\"", ""),
                issueBody(",\"username\":\"ab\""),
                issueBody(",\"temporaryPassword\":\"ngan\""),
        };
        for (String b : bad) {
            mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(b))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(issueCard);
    }

    @Test
    void capTheDocGiaLoaiDocGiaHoacNgayKhongHopLeTra400() throws Exception {
        String url = "/internal/users/" + readerId + "/reader-profile";
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("").replace("STUDENT", "KHACH")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("").replace("2030-01-01", "01/01/2030")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(issueCard);
    }

    @Test
    void capTheDocGiaTrungThiTra409VaLoiNghiepVuTra422() throws Exception {
        String url = "/internal/users/" + readerId + "/reader-profile";
        when(issueCard.execute(any())).thenThrow(new DuplicateReaderException("Số thẻ đã tồn tại"));
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(issueBody("")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_READER"));

        org.mockito.Mockito.doThrow(new InvalidReaderException("Ngày hết hạn phải sau hôm nay"))
                .when(issueCard).execute(any());
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(issueBody("")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_READER"));
    }

    // ---------- PUT card-expiry ----------

    @Test
    void giaHanTheCanKhoaRenew() throws Exception {
        when(renewCard.execute(eq(readerId), eq(LocalDate.of(2031, 1, 1)))).thenReturn(profile(ReaderStatus.ACTIVE));
        String url = "/internal/users/" + readerId + "/reader-profile/card-expiry";
        String body = "{\"newExpiryDate\":\"2031-01-01\"}";

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(url).with(with("ReaderProfileController/changeStatus")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(renewCard);
        mvc.perform(put(url).with(with("ReaderProfileController/renew")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(readerId.toString()));
    }

    @Test
    void giaHanTheThieuNgayTra400VaNgayLoiNghiepVuTra422VaKhongTonTaiTra404() throws Exception {
        String url = "/internal/users/" + readerId + "/reader-profile/card-expiry";
        mvc.perform(put(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        when(renewCard.execute(any(), any())).thenThrow(new InvalidReaderException("Ngày mới phải sau ngày cũ"));
        mvc.perform(put(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newExpiryDate\":\"2020-01-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_READER"));

        org.mockito.Mockito.doThrow(new ReaderProfileNotFoundException(readerId)).when(renewCard).execute(any(), any());
        mvc.perform(put(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newExpiryDate\":\"2031-01-01\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- PUT status ----------

    @Test
    void doiTrangThaiDocGiaCanKhoaChangeStatus() throws Exception {
        when(changeStatus.execute(readerId, ReaderStatus.LOCKED)).thenReturn(profile(ReaderStatus.LOCKED));
        String url = "/internal/users/" + readerId + "/reader-profile/status";
        String body = "{\"status\":\"LOCKED\"}";

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(url).with(with("ReaderProfileController/renew")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(changeStatus);
        mvc.perform(put(url).with(with("ReaderProfileController/changeStatus")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));
    }

    @Test
    void doiTrangThaiThieuHoacSaiGiaTriTra400() throws Exception {
        String url = "/internal/users/" + readerId + "/reader-profile/status";
        mvc.perform(put(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest());
        verify(changeStatus, never()).execute(any(), any());
    }

    @Test
    void doiTrangThaiHoSoKhongTonTaiTra404() throws Exception {
        when(changeStatus.execute(any(), any())).thenThrow(new ReaderProfileNotFoundException(readerId));

        mvc.perform(put("/internal/users/" + readerId + "/reader-profile/status").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
    }

    // ---------- thông tin cá nhân (PUT /{userId}/reader-profile) ----------

    @Test
    void suaThongTinCaNhanCanKhoaReaderProfileControllerUpdateVaTraDuTruong() throws Exception {
        String url = "/internal/users/" + readerId + "/reader-profile";
        String body = "{\"phone\":\"0912 345 678\",\"faculty\":\"Khoa Kinh tế\",\"className\":\"KT-K22A\","
                + "\"memberCode\":\"SV2100123\",\"gender\":\"FEMALE\",\"dateOfBirth\":\"2003-05-20\"}";
        when(updateProfile.execute(eq(readerId), any())).thenReturn(new ReaderProfileResponse(readerId, "SV001",
                "Nguyễn Văn A", "a@x.vn", ReaderType.STUDENT, EXPIRY, ReaderStatus.ACTIVE, true,
                "0912 345 678", LocalDate.of(2003, 5, 20), lms.identity.user.domain.Gender.FEMALE, null,
                "Khoa Kinh tế", "KT-K22A", "SV2100123", null));

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(url).with(with("ReaderProfileController/get")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(put(url).with(with("ReaderProfileController/update")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0912 345 678"))
                .andExpect(jsonPath("$.faculty").value("Khoa Kinh tế"))
                .andExpect(jsonPath("$.className").value("KT-K22A"))
                .andExpect(jsonPath("$.memberCode").value("SV2100123"))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.dateOfBirth").value("2003-05-20"));
    }

    @Test
    void suaThongTinCaNhanSaiDinhDangTra400() throws Exception {
        String url = "/internal/users/" + readerId + "/reader-profile";
        for (String bad : List.of("{\"phone\":\"abc123\"}", "{\"dateOfBirth\":\"2999-01-01\"}",
                "{\"gender\":\"ROBOT\"}", "{\"className\":\"" + "x".repeat(51) + "\"}")) {
            mvc.perform(put(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(updateProfile);
    }

    @Test
    void capTheNhanThongTinCaNhan() throws Exception {
        when(issueCard.execute(any())).thenReturn(profile(ReaderStatus.ACTIVE));
        mvc.perform(post("/internal/users/" + readerId + "/reader-profile").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(",\"phone\":\"+84 912 345 678\",\"faculty\":\"Khoa CNTT\","
                                + "\"memberCode\":\"SV01\",\"cardIssuedDate\":\"2029-01-01\"")))
                .andExpect(status().isCreated());
        ArgumentCaptor<IssueReaderCardCommand> cmd = ArgumentCaptor.forClass(IssueReaderCardCommand.class);
        verify(issueCard).execute(cmd.capture());
        org.junit.jupiter.api.Assertions.assertEquals("Khoa CNTT", cmd.getValue().personalInfo().faculty());
        org.junit.jupiter.api.Assertions.assertEquals("+84 912 345 678", cmd.getValue().personalInfo().phone());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2029, 1, 1),
                cmd.getValue().personalInfo().cardIssuedDate());
    }
}
