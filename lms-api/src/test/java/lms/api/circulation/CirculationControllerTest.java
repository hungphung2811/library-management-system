package lms.api.circulation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.ExpirationResult;
import lms.api.circulation.dto.ReservationView;
import lms.api.circulation.web.BorrowingController;
import lms.api.circulation.web.ReservationController;
import lms.api.config.SecurityConfiguration;
import lms.api.web.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Hợp đồng FE của circulation: xác thực, ánh xạ DTO, chuyển nguyên lỗi 4xx, mất kết nối thành 503. */
@WebMvcTest({BorrowingController.class, ReservationController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CirculationControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CirculationClient circulation;
    @MockitoBean JwtDecoder jwtDecoder;

    static final UUID ID = UUID.randomUUID();
    static final UUID USER = UUID.randomUUID();
    static final UUID COPY = UUID.randomUUID();
    static final UUID TITLE = UUID.randomUUID();

    private static HttpClientErrorException loi(HttpStatus s, String body) {
        return HttpClientErrorException.create(s, s.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/borrowings/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/borrowings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reservations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reservations/expirations")).andExpect(status().isUnauthorized());
    }

    @Test
    void muonSachTraCreatedVaLocationTheoDuongDanFe() throws Exception {
        when(circulation.borrow(any())).thenReturn(new BorrowingView(ID, USER, "BORROWING", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 15), 0, List.of(new BorrowingView.Item(COPY, null))));
        mvc.perform(post("/api/borrowings").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + USER + "\",\"bookCopyIds\":[\"" + COPY + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/borrowings/" + ID))
                .andExpect(jsonPath("$.items[0].bookCopyId").value(COPY.toString()))
                .andExpect(jsonPath("$.dueDate").value("2026-01-15"));
    }

    @Test
    void duLieuSaiBi400TruocKhiGoiService() throws Exception {
        mvc.perform(post("/api/borrowings").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + USER + "\",\"bookCopyIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/reservations").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void datTruocVaDanhSachAnhXaDto() throws Exception {
        ReservationView view = new ReservationView(ID, TITLE, "WAITING", 2, Instant.parse("2026-01-01T00:00:00Z"),
                null, null);
        when(circulation.reserve(TITLE)).thenReturn(view);
        when(circulation.myReservations()).thenReturn(List.of(view));
        when(circulation.cancelReservation(ID)).thenReturn(view);
        mvc.perform(post("/api/reservations").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + TITLE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/reservations/" + ID));
        mvc.perform(get("/api/reservations").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].queuePosition").value(2))
                .andExpect(jsonPath("$[0].status").value("WAITING"));
        mvc.perform(delete("/api/reservations/" + ID).with(jwt())).andExpect(status().isOk());
    }

    @Test
    void hetHanDatTruocThuCong() throws Exception {
        when(circulation.expireReservations()).thenReturn(new ExpirationResult(3));
        mvc.perform(post("/api/reservations/expirations").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expired").value(3));
    }

    @Test
    void loi4xxCuaServiceNoiBoDuocChuyenNguyen() throws Exception {
        String body = "{\"code\":\"RESERVATION_NOT_FOUND\",\"message\":\"Không tìm thấy\",\"details\":[]}";
        when(circulation.cancelReservation(ID)).thenThrow(loi(HttpStatus.NOT_FOUND, body));
        mvc.perform(delete("/api/reservations/" + ID).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        when(circulation.renew(ID)).thenThrow(loi(HttpStatus.FORBIDDEN, ""));
        mvc.perform(post("/api/borrowings/" + ID + "/renewals").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_REJECTED"));
    }

    @Test
    void matKetNoiThanh503() throws Exception {
        when(circulation.getBorrowing(ID)).thenThrow(new ResourceAccessException("down"));
        mvc.perform(get("/api/borrowings/" + ID).with(jwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }
}
