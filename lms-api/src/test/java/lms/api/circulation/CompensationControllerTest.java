package lms.api.circulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.CompensationViews;
import lms.api.circulation.dto.ReportLostRequest;
import lms.api.circulation.dto.ReturnBookRequest;
import lms.api.circulation.web.BorrowingController;
import lms.api.circulation.web.CompensationController;
import lms.api.config.SecurityConfiguration;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/**
 * Hợp đồng FE của đền bù (/api/circulation/compensation-policy, damage-levels, /api/fines/adjustment-requests,
 * /api/fines/refund-requests, POST /api/borrowings/found, trả/báo mất kèm bậc, ghi chú, ảnh): 401, ánh xạ DTO, validation,
 * chuyển nguyên lỗi 403/409 của circulation-service (khóa và APPROVER_MUST_DIFFER do service kiểm).
 */
@WebMvcTest({CompensationController.class, BorrowingController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CompensationControllerTest {

    static final UUID FINE = UUID.randomUUID();
    static final UUID REQ = UUID.randomUUID();
    static final UUID COPY = UUID.randomUUID();
    static final UUID BORROWING = UUID.randomUUID();
    static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired MockMvc mvc;
    @MockitoBean CirculationClient circulation;
    @MockitoBean JwtDecoder jwtDecoder;

    private static HttpClientErrorException loi(HttpStatus s, String body) {
        return HttpClientErrorException.create(s, s.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static CompensationViews.Adjustment adjustment(String status) {
        return new CompensationViews.Adjustment(REQ, FINE, UUID.randomUUID(), "LOST", new BigDecimal("150000"), "WAIVE",
                null, "Độc giả khó khăn", status, UUID.randomUUID(), Instant.parse("2026-11-18T01:00:00Z"), null, null,
                null, UUID.randomUUID());
    }

    @Test
    void moiDuongDanMoiKhongTokenBi401VaKhongGoiService() throws Exception {
        String[] gets = {"/api/circulation/compensation-policy", "/api/circulation/damage-levels",
                "/api/fines/adjustment-requests", "/api/fines/refund-requests"};
        for (String p : gets) mvc.perform(get(p)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/circulation/compensation-policy").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/circulation/damage-levels").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/circulation/damage-levels/MINOR")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/fines/" + FINE + "/adjustment-requests").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/fines/adjustment-requests/" + REQ + "/approve")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/fines/refund-requests/" + REQ + "/reject")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/borrowings/found").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(circulation);
    }

    @Test
    void chinhSachDenBuVaBacHong() throws Exception {
        when(circulation.getCompensationPolicy()).thenReturn(new CompensationViews.CompensationPolicy(
                new BigDecimal("1.50"), "COVER_PRICE", null, null, 30, BigDecimal.ZERO, false, 0, null));
        mvc.perform(get("/api/circulation/compensation-policy").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lostRate").value(1.5))
                .andExpect(jsonPath("$.custom").value(false))
                .andExpect(jsonPath("$.foundWindowDays").value(30));

        mvc.perform(put("/api/circulation/compensation-policy").with(jwt()).contentType(JSON)
                        .content("{\"lostRate\":1.5,\"priceSource\":\"MARKET\",\"foundWindowDays\":30}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put("/api/circulation/compensation-policy").with(jwt()).contentType(JSON)
                        .content("{\"priceSource\":\"COVER_PRICE\",\"foundWindowDays\":30}"))
                .andExpect(status().isBadRequest());
        verify(circulation, never()).updateCompensationPolicy(any());

        when(circulation.listDamageLevels()).thenReturn(new CompensationViews.DamageLevelList(false, 10, List.of(
                new CompensationViews.DamageLevel("MINOR", "Hỏng nhẹ", new BigDecimal("0.20"), 1, true, 0, null))));
        mvc.perform(get("/api/circulation/damage-levels").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxLevels").value(10))
                .andExpect(jsonPath("$.items[0].code").value("MINOR"));

        when(circulation.createDamageLevel(any())).thenReturn(
                new CompensationViews.DamageLevel("LIGHT", "Nhẹ", new BigDecimal("0.10"), 0, true, 0, null));
        mvc.perform(post("/api/circulation/damage-levels").with(jwt()).contentType(JSON)
                        .content("{\"code\":\"LIGHT\",\"label\":\"Nhẹ\",\"rate\":0.10}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("LIGHT"));
        mvc.perform(post("/api/circulation/damage-levels").with(jwt()).contentType(JSON)
                .content("{\"code\":\"\",\"label\":\"Nhẹ\",\"rate\":0.10}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/circulation/damage-levels").with(jwt()).contentType(JSON)
                .content("{\"code\":\"X\",\"label\":\"Nhẹ\"}")).andExpect(status().isBadRequest());

        org.mockito.Mockito.doThrow(loi(HttpStatus.CONFLICT, "{\"code\":\"DAMAGE_LEVEL_IN_USE\",\"message\":\"đã dùng\"}"))
                .when(circulation).deleteDamageLevel("USED");
        mvc.perform(delete("/api/circulation/damage-levels/USED").with(jwt()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DAMAGE_LEVEL_IN_USE"));
        mvc.perform(delete("/api/circulation/damage-levels/FREE").with(jwt())).andExpect(status().isNoContent());
        verify(circulation).deleteDamageLevel("FREE");

        when(circulation.updateDamageLevel(eq("LIGHT"), any())).thenReturn(
                new CompensationViews.DamageLevel("LIGHT", "Nhẹ+", new BigDecimal("0.15"), 1, false, 1, null));
        mvc.perform(put("/api/circulation/damage-levels/LIGHT").with(jwt()).contentType(JSON)
                        .content("{\"label\":\"Nhẹ+\",\"rate\":0.15,\"sortOrder\":1,\"active\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(put("/api/circulation/damage-levels/LIGHT").with(jwt()).contentType(JSON)
                .content("{\"label\":\"x\",\"rate\":0.15}")).andExpect(status().isBadRequest());
    }

    @Test
    void deNghiMienGiamValidationVaChuyenTiep() throws Exception {
        when(circulation.requestAdjustment(eq(FINE), any())).thenReturn(adjustment("PENDING"));
        mvc.perform(post("/api/fines/" + FINE + "/adjustment-requests").with(jwt()).contentType(JSON)
                        .content("{\"type\":\"WAIVE\",\"reason\":\"Độc giả khó khăn\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/fines/" + FINE + "/adjustment-requests").with(jwt()).contentType(JSON)
                .content("{\"type\":\"HALF\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/fines/" + FINE + "/adjustment-requests").with(jwt()).contentType(JSON)
                .content("{\"type\":\"WAIVE\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/fines/" + FINE + "/adjustment-requests").with(jwt()).contentType(JSON)
                .content("{\"type\":\"WAIVE\",\"reason\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/fines/not-a-uuid/adjustment-requests").with(jwt()).contentType(JSON)
                .content("{\"type\":\"WAIVE\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void duyetKhongBodyVaLoi403Va409ChuyenNguyen() throws Exception {
        when(circulation.approveAdjustment(eq(REQ), any())).thenReturn(adjustment("APPROVED"));
        mvc.perform(post("/api/fines/adjustment-requests/" + REQ + "/approve").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        ArgumentCaptor<CompensationViews.Decision> d = ArgumentCaptor.forClass(CompensationViews.Decision.class);
        verify(circulation).approveAdjustment(eq(REQ), d.capture());
        assertThat(d.getValue()).isNull();

        when(circulation.rejectAdjustment(eq(REQ), any())).thenThrow(loi(HttpStatus.CONFLICT,
                "{\"code\":\"APPROVER_MUST_DIFFER\",\"message\":\"Người duyệt phải khác người đề nghị\"}"));
        mvc.perform(post("/api/fines/adjustment-requests/" + REQ + "/reject").with(jwt()).contentType(JSON)
                        .content("{\"note\":\"không\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPROVER_MUST_DIFFER"));

        when(circulation.approveRefund(eq(REQ), any())).thenThrow(loi(HttpStatus.FORBIDDEN,
                "{\"code\":\"FORBIDDEN\",\"message\":\"Không đủ quyền\"}"));
        mvc.perform(post("/api/fines/refund-requests/" + REQ + "/approve").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/fines/refund-requests/" + REQ + "/reject").with(jwt()).contentType(JSON)
                .content("{\"note\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void danhSachTruyenQueryRecordNguyenVan() throws Exception {
        when(circulation.listAdjustments(any()))
                .thenReturn(new CompensationViews.Page<>(List.of(adjustment("PENDING")), 1, 0, 20));
        mvc.perform(get("/api/fines/adjustment-requests").with(jwt()).param("status", "PENDING")
                        .param("fineId", FINE.toString()).param("page", "2").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].fineId").value(FINE.toString()));
        ArgumentCaptor<CompensationViews.AdjustmentSearch> q = ArgumentCaptor.forClass(CompensationViews.AdjustmentSearch.class);
        verify(circulation).listAdjustments(q.capture());
        assertThat(q.getValue()).isEqualTo(new CompensationViews.AdjustmentSearch("PENDING", FINE, null, 2, 5));
        mvc.perform(get("/api/fines/refund-requests").with(jwt()).param("readerId", "khong-phai-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ghiNhanTimLaiValidationVaLoiNghiepVu() throws Exception {
        when(circulation.reportFound(any())).thenThrow(loi(HttpStatus.CONFLICT,
                "{\"code\":\"REFUND_WINDOW_EXPIRED\",\"message\":\"Quá hạn\"}"));
        mvc.perform(post("/api/borrowings/found").with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                        + "\",\"restoreStatus\":\"AVAILABLE\",\"refundMethod\":\"CASH\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_WINDOW_EXPIRED"));
        mvc.perform(post("/api/borrowings/found").with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"restoreStatus\":\"LOANED\",\"refundMethod\":\"CASH\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/borrowings/found").with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"restoreStatus\":\"AVAILABLE\",\"refundMethod\":\"PAYPAL\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/borrowings/found").with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/borrowings/found").with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"restoreStatus\":\"DAMAGED\",\"refundMethod\":\"BANK_TRANSFER\",\"checkNote\":\""
                + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
        verify(circulation, times(1)).reportFound(any());
    }

    @Test
    void traBaoMatKemBacHongGhiChuAnhChuyenNguyenVaChanQuaGioiHan() throws Exception {
        mvc.perform(post("/api/borrowings/" + BORROWING + "/returns").with(jwt()).contentType(JSON)
                        .content("{\"bookCopyId\":\"" + COPY + "\",\"condition\":\"DAMAGED\",\"damageLevelCode\":\"SEVERE\","
                                + "\"damageNote\":\"Rách bìa\",\"evidenceUrls\":[\"https://img.example/a.jpg\"]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<ReturnBookRequest> c = ArgumentCaptor.forClass(ReturnBookRequest.class);
        verify(circulation).returnBook(eq(BORROWING), c.capture());
        assertThat(c.getValue().damageLevelCode()).isEqualTo("SEVERE");
        assertThat(c.getValue().evidenceUrls()).containsExactly("https://img.example/a.jpg");

        String url = "\"https://a/b\"";
        String four = url + "," + url + "," + url + "," + url;
        String ret = "/api/borrowings/" + BORROWING + "/returns";
        mvc.perform(post(ret).with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"condition\":\"DAMAGED\",\"evidenceUrls\":[" + four + "]}")).andExpect(status().isBadRequest());
        mvc.perform(post(ret).with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"condition\":\"DAMAGED\",\"evidenceUrls\":[\"https://a/" + "x".repeat(500) + "\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ret).with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"condition\":\"DAMAGED\",\"damageLevelCode\":\"" + "A".repeat(31) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ret).with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"condition\":\"DAMAGED\",\"damageNote\":\"" + "x".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ret).with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"condition\":\"LOST\"}")).andExpect(status().isBadRequest());
        verify(circulation, times(1)).returnBook(eq(BORROWING), any());

        mvc.perform(post("/api/borrowings/losses").with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"damageNote\":\"Mất ở xe buýt\",\"evidenceUrls\":[" + url + "]}")).andExpect(status().isOk());
        ArgumentCaptor<ReportLostRequest> l = ArgumentCaptor.forClass(ReportLostRequest.class);
        verify(circulation).reportLost(l.capture());
        assertThat(l.getValue().damageNote()).isEqualTo("Mất ở xe buýt");
        mvc.perform(post("/api/borrowings/losses").with(jwt()).contentType(JSON).content("{\"bookCopyId\":\"" + COPY
                + "\",\"evidenceUrls\":[" + four + "]}")).andExpect(status().isBadRequest());
        verify(circulation, times(1)).reportLost(any());
    }
}
