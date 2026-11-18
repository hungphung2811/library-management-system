package lms.api.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.sales.client.SalesShiftClient;
import lms.api.sales.client.http.HttpSalesShiftClient;
import lms.api.sales.dto.SalesDtos.SalesPage;
import lms.api.sales.dto.SalesShiftDtos.CloseShiftRequest;
import lms.api.sales.dto.SalesShiftDtos.MyShiftsQuery;
import lms.api.sales.dto.SalesShiftDtos.ShiftDetailView;
import lms.api.sales.dto.SalesShiftDtos.ShiftMethodLineView;
import lms.api.sales.dto.SalesShiftDtos.ShiftReportView;
import lms.api.sales.dto.SalesShiftDtos.ShiftSearchQuery;
import lms.api.sales.dto.SalesShiftDtos.ShiftSettingsView;
import lms.api.sales.dto.SalesShiftDtos.ShiftView;
import lms.api.sales.web.SalesShiftController;
import lms.api.support.TokenRelayInterceptor;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Hợp đồng FE của ca thu ngân (ADR-0030 gói B) ở lms-api: /api/sales/shifts/** và /api/sales/shift-settings. */
@WebMvcTest(SalesShiftController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SalesShiftControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SalesShiftClient shifts;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID CASHIER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-19T01:00:00Z");

    private static ShiftView shift(String status, String phase) {
        return new ShiftView(ID, "CA-000001", CASHIER, "Quầy 1", status, phase, T0, 500_000, null, null, null, null,
                null, null, "NONE", null, null, null, 0, 0);
    }

    private static ShiftDetailView detail() {
        List<ShiftMethodLineView> lines = List.of(new ShiftMethodLineView("CASH", 2, 300_000, 1, 50_000, 250_000, 0,
                0, 0, 0));
        return new ShiftDetailView(shift("OPEN", "OPEN"), new ShiftReportView(2, 300_000, 0, lines, 300_000, 50_000,
                250_000), 750_000L, List.of());
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/sales/shifts/me/current")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sales/shifts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"openingFloat\":0,\"clientRequestId\":\"a\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sales/shifts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sales/shift-settings")).andExpect(status().isUnauthorized());
        verifyNoInteractions(shifts);
    }

    @Test
    void moCaTraCreatedVaKiemDauVao() throws Exception {
        when(shifts.openShift(any())).thenReturn(shift("OPEN", "OPEN"));
        mvc.perform(post("/api/sales/shifts").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingFloat\":500000,\"registerLabel\":\"Quầy 1\",\"clientRequestId\":\"c-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shiftNo").value("CA-000001"))
                .andExpect(jsonPath("$.phase").value("OPEN"))
                .andExpect(jsonPath("$.openingFloat").value(500000));
        // tiền đầu ca âm, thiếu clientRequestId: chặn ở BFF
        mvc.perform(post("/api/sales/shifts").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"openingFloat\":-1,\"clientRequestId\":\"c-1\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/shifts").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"openingFloat\":0}")).andExpect(status().isBadRequest());
    }

    @Test
    void caCuaToiChuyenSangClient() throws Exception {
        when(shifts.currentShift()).thenReturn(detail());
        when(shifts.myShift(ID)).thenReturn(detail());
        when(shifts.myShifts(any())).thenReturn(new SalesPage<>(List.of(shift("CLOSED", "CLOSED")), 1, 0, 20));
        when(shifts.closeCurrentShift(any())).thenReturn(shift("CLOSED", "PENDING_APPROVAL"));

        mvc.perform(get("/api/sales/shifts/me/current").with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.provisionalExpectedCash").value(750000))
                .andExpect(jsonPath("$.report.methods[0].method").value("CASH"))
                .andExpect(jsonPath("$.report.methods[0].collected").value(300000));
        mvc.perform(get("/api/sales/shifts/me/" + ID).with(jwt())).andExpect(status().isOk());
        mvc.perform(get("/api/sales/shifts/me").queryParam("status", "CLOSED").queryParam("page", "1")
                        .queryParam("size", "5").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        ArgumentCaptor<MyShiftsQuery> q = ArgumentCaptor.forClass(MyShiftsQuery.class);
        verify(shifts).myShifts(q.capture());
        assertEquals("CLOSED", q.getValue().status());
        assertEquals(1, q.getValue().page());
        assertEquals(5, q.getValue().size());

        mvc.perform(post("/api/sales/shifts/me/current/closing").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countedCash\":740000,\"varianceReason\":\"Thối nhầm\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("PENDING_APPROVAL"));
        mvc.perform(post("/api/sales/shifts/me/current/closing").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":-5}")).andExpect(status().isBadRequest());
    }

    @Test
    void quanLyTimDongThayMoLaiDuyetVaCauHinh() throws Exception {
        when(shifts.searchShifts(any())).thenReturn(new SalesPage<>(List.of(shift("CLOSED", "PENDING_APPROVAL")), 1,
                0, 20));
        when(shifts.getShift(ID)).thenReturn(detail());
        when(shifts.closeShiftOnBehalf(eq(ID), any())).thenReturn(shift("CLOSED", "CLOSED"));
        when(shifts.reopenShift(eq(ID), any())).thenReturn(shift("OPEN", "REOPENED"));
        when(shifts.approveShiftVariance(eq(ID), any())).thenReturn(shift("CLOSED", "CLOSED"));
        when(shifts.getShiftSettings()).thenReturn(new ShiftSettingsView(false, 0, 0, null));
        when(shifts.updateShiftSettings(any())).thenReturn(new ShiftSettingsView(true, 5000, 1, T0));

        mvc.perform(get("/api/sales/shifts").queryParam("cashierUserId", CASHIER.toString())
                        .queryParam("varianceStatus", "PENDING_APPROVAL").queryParam("from", "2026-11-01T00:00:00Z")
                        .with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].phase").value("PENDING_APPROVAL"));
        ArgumentCaptor<ShiftSearchQuery> q = ArgumentCaptor.forClass(ShiftSearchQuery.class);
        verify(shifts).searchShifts(q.capture());
        assertEquals(CASHIER, q.getValue().cashierUserId());
        assertEquals("PENDING_APPROVAL", q.getValue().varianceStatus());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), q.getValue().from());

        mvc.perform(get("/api/sales/shifts/" + ID).with(jwt())).andExpect(status().isOk());
        mvc.perform(post("/api/sales/shifts/" + ID + "/closing").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":0}")).andExpect(status().isOk());
        mvc.perform(post("/api/sales/shifts/" + ID + "/reopening").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Đếm nhầm\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REOPENED"));
        mvc.perform(post("/api/sales/shifts/" + ID + "/variance-approval").with(jwt())).andExpect(status().isOk());
        mvc.perform(post("/api/sales/shifts/" + ID + "/variance-approval").with(jwt())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ok\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/sales/shift-settings").with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.requireOpenShiftForPos").value(false));
        mvc.perform(put("/api/sales/shift-settings").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireOpenShiftForPos\":true,\"varianceThreshold\":5000,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.varianceThreshold").value(5000));
        mvc.perform(put("/api/sales/shift-settings").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requireOpenShiftForPos\":true,\"varianceThreshold\":-1,\"version\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loiNghiepVuCuaSalesDuocChuyenNguyen() throws Exception {
        String body = "{\"code\":\"SHIFT_ALREADY_OPEN\",\"message\":\"x\",\"details\":[]}";
        when(shifts.openShift(any())).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "err",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(post("/api/sales/shifts").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingFloat\":0,\"clientRequestId\":\"c-2\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_ALREADY_OPEN"));
    }

    // ---- client HTTP ----

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void clientGoiDungDuongDanVaChuyenTiepToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sales")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSalesShiftClient client = new HttpSalesShiftClient(builder.build());
        Jwt jwt = Jwt.withTokenValue("token-nv").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        String shiftJson = "{\"id\":\"" + ID + "\",\"shiftNo\":\"CA-000001\",\"cashierUserId\":\"" + CASHIER
                + "\",\"status\":\"OPEN\",\"phase\":\"OPEN\",\"openingFloat\":0,\"reopenCount\":0,\"version\":0,"
                + "\"truongMoi\":1}";
        String page = "{\"items\":[" + shiftJson + "],\"total\":1,\"page\":0,\"size\":20}";

        server.expect(requestTo("http://sales/internal/sales/shifts")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-nv"))
                .andRespond(withSuccess(shiftJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shifts/me/current")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"shift\":" + shiftJson + ",\"report\":null,\"history\":[]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shifts/me?status=CLOSED&page=0"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shifts/me/current/closing"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(shiftJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shifts?cashierUserId=" + CASHIER + "&varianceStatus=PENDING_APPROVAL"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shifts/" + ID + "/closing"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(shiftJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shifts/" + ID + "/reopening"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(shiftJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shifts/" + ID + "/variance-approval"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(shiftJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shift-settings")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"requireOpenShiftForPos\":false,\"varianceThreshold\":0,\"version\":0}",
                        MediaType.APPLICATION_JSON));

        assertEquals("CA-000001", client.openShift(new lms.api.sales.dto.SalesShiftDtos.OpenShiftRequest(0L, null,
                "c-1")).shiftNo());
        assertNull(client.currentShift().report());
        assertEquals(1, client.myShifts(new MyShiftsQuery("CLOSED", 0, null)).total());
        assertEquals("OPEN", client.closeCurrentShift(new CloseShiftRequest(0L, null)).status());
        assertEquals(1, client.searchShifts(new ShiftSearchQuery(CASHIER, null, "PENDING_APPROVAL", null, null, null,
                null)).total());
        client.closeShiftOnBehalf(ID, new CloseShiftRequest(0L, null));
        client.reopenShift(ID, new lms.api.sales.dto.SalesShiftDtos.ReopenShiftRequest("lý do"));
        client.approveShiftVariance(ID, null);
        assertEquals(false, client.getShiftSettings().requireOpenShiftForPos());
        server.verify();
    }
}
