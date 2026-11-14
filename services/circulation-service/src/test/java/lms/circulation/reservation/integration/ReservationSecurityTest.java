package lms.circulation.reservation.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.circulation.config.SecurityConfiguration;
import lms.circulation.reservation.application.CancelReservationUseCase;
import lms.circulation.reservation.application.ListMyReservationsUseCase;
import lms.circulation.reservation.application.ReservationResponse;
import lms.circulation.reservation.application.ReserveTitleUseCase;
import lms.circulation.reservation.domain.ReservationStatus;
import lms.circulation.reservation.infrastructure.web.ReservationController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.time.Instant;
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

/** RBAC cho đặt trước: không token, thiếu permission, đủ permission; readerId lấy từ claim sub. Không cần Docker. */
@WebMvcTest(ReservationController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ReservationSecurityTest {

    @Autowired
    MockMvc mvc;

    /** Middleware phân quyền theo endpoint cần registrar; ở đây dùng bản giả để không gọi mạng. */
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @MockitoBean ReserveTitleUseCase reserve;
    @MockitoBean CancelReservationUseCase cancel;
    @MockitoBean ListMyReservationsUseCase listMine;
    @MockitoBean lms.circulation.reservation.application.ExpireOverdueReservationsUseCase expire;
    @MockitoBean JwtDecoder jwtDecoder;

    static final UUID READER = UUID.randomUUID();
    static final UUID TITLE = UUID.randomUUID();
    static final String BODY = "{\"titleId\":\"" + TITLE + "\"}";

    private ReservationResponse response() {
        return new ReservationResponse(UUID.randomUUID(), TITLE, ReservationStatus.WAITING, 1, Instant.now(), null, null);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post("/internal/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/reservations")).andExpect(status().isUnauthorized());
    }

    @Test
    void thieuPermissionReserveBookBi403() throws Exception {
        var token = jwt().jwt(j -> j.subject(READER.toString())).authorities(new SimpleGrantedAuthority("BOOK_VIEW"));
        mvc.perform(post("/internal/reservations").with(token).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/reservations").with(token)).andExpect(status().isForbidden());
        mvc.perform(delete("/internal/reservations/" + UUID.randomUUID()).with(token)).andExpect(status().isForbidden());
    }

    @Test
    void duPermissionDatTruocBangDanhTinhTrongToken() throws Exception {
        when(reserve.execute(eq(READER), eq(TITLE))).thenReturn(response());
        var token = jwt().jwt(j -> j.subject(READER.toString())).authorities(new SimpleGrantedAuthority("ReservationController/reserve"));

        mvc.perform(post("/internal/reservations").with(token).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.queuePosition").value(1));
    }

    @Test
    void huyVaXemDanhSachDungReaderTrongToken() throws Exception {
        UUID id = UUID.randomUUID();
        when(cancel.execute(eq(READER), eq(id))).thenReturn(response());
        when(listMine.execute(any())).thenReturn(List.of(response()));
        var token = jwt().jwt(j -> j.subject(READER.toString())).authorities(new SimpleGrantedAuthority("ReservationController/cancel"), new SimpleGrantedAuthority("ReservationController/mine"));

        mvc.perform(delete("/internal/reservations/" + id).with(token)).andExpect(status().isOk());
        mvc.perform(get("/internal/reservations").with(token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].queuePosition").value(1));
    }

    @Test
    void validationBodyThieuTitleIdBi400() throws Exception {
        var token = jwt().jwt(j -> j.subject(READER.toString())).authorities(new SimpleGrantedAuthority("ReservationController/reserve"));
        mvc.perform(post("/internal/reservations").with(token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chayThuCongUC14CanPermissionApproveBorrowing() throws Exception {
        when(expire.execute()).thenReturn(2);
        var reader = jwt().jwt(j -> j.subject(READER.toString())).authorities(new SimpleGrantedAuthority("ReservationController/reserve"));
        var librarian = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ReservationController/expireNow"));

        mvc.perform(post("/internal/reservations/expirations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/reservations/expirations").with(reader)).andExpect(status().isForbidden());
        mvc.perform(post("/internal/reservations/expirations").with(librarian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expired").value(2));
    }
}
