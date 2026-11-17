package lms.api.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import lms.api.config.SecurityConfiguration;
import lms.api.reports.client.ReportClient;
import lms.api.reports.client.ReportSink;
import lms.api.reports.dto.OrdersReportQuery;
import lms.api.reports.dto.ReportHeaders;
import lms.api.reports.web.ReportController;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/** /api/reports/**: chuyển luồng CSV giữ nguyên header, lỗi trước byte đầu là JSON, ánh xạ query. */
@WebMvcTest(ReportController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class ReportControllerTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Autowired MockMvc mvc;
    @MockitoBean ReportClient reports;
    @MockitoBean JwtDecoder jwtDecoder;

    private static byte[] csv(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, all, 0, BOM.length);
        System.arraycopy(body, 0, all, BOM.length, body.length);
        return all;
    }

    private static org.mockito.stubbing.Stubber replyWith(ReportHeaders headers, byte[] body, int sinkIndex) {
        return doAnswer(inv -> {
            ReportSink sink = inv.getArgument(sinkIndex);
            sink.accept(headers, new ByteArrayInputStream(body));
            return null;
        });
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (String path : new String[] {"/api/reports/overdue-borrowings", "/api/reports/stock", "/api/reports/orders"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(reports);
    }

    @Test
    void quaHanChuyenNguyenByteVaBaHeader() throws Exception {
        byte[] body = csv("borrowingId,userId\r\n1,2\r\n");
        replyWith(new ReportHeaders("text/csv; charset=UTF-8", "attachment; filename=\"overdue-borrowings-20261117.csv\"",
                "true"), body, 0).when(reports).overdueBorrowings(any());

        mvc.perform(get("/api/reports/overdue-borrowings").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"overdue-borrowings-20261117.csv\""))
                .andExpect(header().string("X-Report-Truncated", "true"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(body));
    }

    @Test
    void khongCoHeaderCatNganThiKhongGuiHeaderNay() throws Exception {
        replyWith(new ReportHeaders("text/csv; charset=UTF-8", "attachment; filename=\"stock.csv\"", null),
                csv("titleId\r\n"), 1).when(reports).stock(any(), any());

        mvc.perform(get("/api/reports/stock").queryParam("lowStockOnly", "true").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Report-Truncated"));
        verify(reports).stock(org.mockito.ArgumentMatchers.eq("true"), any());
    }

    @Test
    void donHangAnhXaBonQueryVaThieuContentTypeThiMacDinhCsvUtf8() throws Exception {
        replyWith(new ReportHeaders(null, null, null), csv("orderNo\r\n"), 1).when(reports).orders(any(), any());

        mvc.perform(get("/api/reports/orders").queryParam("from", "2026-11-01").queryParam("to", "2026-11-17")
                        .queryParam("channel", "POS").queryParam("status", "COMPLETED").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8"));

        verify(reports).orders(org.mockito.ArgumentMatchers.eq(new OrdersReportQuery("2026-11-01", "2026-11-17", "POS",
                "COMPLETED")), any());
    }

    @Test
    void loiTruocByteDauLaJsonVaGiuMaCuaService() throws Exception {
        String body = "{\"code\":\"INVALID_RANGE\",\"message\":\"x\",\"details\":[]}";
        doThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).when(reports).orders(any(), any());
        doThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "x", HttpHeaders.EMPTY,
                "{\"code\":\"FORBIDDEN\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .when(reports).overdueBorrowings(any());

        mvc.perform(get("/api/reports/orders").with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get("/api/reports/overdue-borrowings").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
