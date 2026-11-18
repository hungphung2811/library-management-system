package lms.api.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.sales.client.SalesInvoiceClient;
import lms.api.sales.client.http.HttpSalesInvoiceClient;
import lms.api.sales.dto.SalesDtos.SalesPage;
import lms.api.sales.dto.SalesInvoiceDtos.BuyerView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceLineView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSearchQuery;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSummaryView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceView;
import lms.api.sales.dto.SalesInvoiceDtos.PrintedInvoice;
import lms.api.sales.dto.SalesInvoiceDtos.SellerView;
import lms.api.sales.web.SalesInvoiceController;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Hợp đồng FE của hóa đơn nội bộ (ADR-0030 gói A) ở lms-api: /api/sales/invoices/**, invoice-request, invoice-settings. */
@WebMvcTest(SalesInvoiceController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SalesInvoiceTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean SalesInvoiceClient invoices;
    @MockitoBean JwtDecoder jwtDecoder;

    private static InvoiceView view() {
        return new InvoiceView(ID, "ORIGINAL", "ISSUED", ORDER, "DH-000001", null, null, null, "1", "C26TAA", 2026, 1L,
                "00000001", Instant.parse("2026-11-19T03:00:00Z"), null, null, null, null,
                new SellerView("Nhà sách A", "0123456789", "1 Lê Lợi", null, null),
                new BuyerView("Người mua không lấy hóa đơn", null, null, null, null), "VND", true, "CASH", 100_000, 5_000,
                105_000, "Một trăm linh năm nghìn đồng", "INTERNAL", null, Instant.parse("2026-11-19T03:00:00Z"), 0,
                List.of(new InvoiceLineView(1, "Sách", "I", "cuốn", 1, 105_000, 0, 100_000, 500, 5_000, 105_000)),
                List.of(), "Hóa đơn nội bộ, không thay thế hóa đơn điện tử");
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/sales/invoices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sales/invoices/" + ID + "/print")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sales/orders/" + ORDER + "/invoice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"k\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(invoices);
    }

    @Test
    void timKiemGomQueryVaChuyenSangClient() throws Exception {
        when(invoices.searchInvoices(any())).thenReturn(new SalesPage<>(List.of(new InvoiceSummaryView(ID, "ORIGINAL",
                "ISSUED", ORDER, "DH-000001", null, "1", "C26TAA", 2026, 1L, "00000001", "An", null, null, 105_000,
                Instant.parse("2026-11-19T03:00:00Z"), Instant.parse("2026-11-19T03:00:00Z"))), 1, 0, 20));
        mvc.perform(get("/api/sales/invoices").param("status", "ISSUED").param("kind", "ORIGINAL")
                        .param("keyword", "DH-1").param("page", "1").param("size", "5").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].invoiceNumber").value("00000001"))
                .andExpect(jsonPath("$.total").value(1));
        verify(invoices).searchInvoices(new InvoiceSearchQuery("ISSUED", "ORIGINAL", null, null, "DH-1", 1, 5));
    }

    @Test
    void chiTietVaLapHoaDonTra201CungLocation() throws Exception {
        when(invoices.getInvoice(ID)).thenReturn(view());
        when(invoices.issueInvoice(eq(ORDER), any())).thenReturn(view());
        mvc.perform(get("/api/sales/invoices/" + ID).with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").value("Hóa đơn nội bộ, không thay thế hóa đơn điện tử"))
                .andExpect(jsonPath("$.lines[0].vatRateBps").value(500));
        mvc.perform(post("/api/sales/orders/" + ORDER + "/invoice").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"k-1\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/sales/invoices/" + ID));
        mvc.perform(post("/api/sales/orders/" + ORDER + "/invoice").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/invoices/" + ID + "/cancellation").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/invoices/" + ID + "/replacement").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"sai\"}")).andExpect(status().isBadRequest()); // thiếu clientRequestId
    }

    @Test
    void banInLuonLaHtmlVoiHeaderAnToanBatKeSalesTraGi() throws Exception {
        when(invoices.printInvoice(ID)).thenReturn(new PrintedInvoice("application/x-evil",
                "<html>ok</html>".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(get("/api/sales/invoices/" + ID + "/print").with(jwt()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void thayTheVaYeuCauVaCauHinhChuyenBody() throws Exception {
        when(invoices.replaceInvoice(eq(ID), any())).thenReturn(view());
        mvc.perform(post("/api/sales/invoices/" + ID + "/replacement").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"sai\",\"buyer\":{\"company\":\"Cty\",\"taxCode\":\"0123456789\","
                                + "\"address\":\"đc\"},\"clientRequestId\":\"k2\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/sales/orders/" + ORDER + "/invoice-request").with(jwt())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/sales/invoice-settings").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sellerLegalName\":\"A\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void loiNghiepVuCuaSalesDuocChuyenNguyen() throws Exception {
        String body = "{\"code\":\"INVOICE_HAS_ADJUSTMENTS\",\"message\":\"x\",\"details\":[]}";
        when(invoices.cancelInvoice(eq(ID), any())).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "err",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(post("/api/sales/invoices/" + ID + "/cancellation").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"sai\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_HAS_ADJUSTMENTS"));
    }

    // ---- client HTTP ----

    private static final class Stub {
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales");
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final HttpSalesInvoiceClient client = new HttpSalesInvoiceClient(builder.build());
    }

    @Test
    void clientChuyenQueryVaDuongDanDungVaLayBanInNguyenVan() {
        Stub a = new Stub();
        a.server.expect(requestTo("http://sales/internal/sales/invoices?status=ISSUED&keyword=DH&page=0&size=10"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":0,\"size\":10}",
                        MediaType.APPLICATION_JSON));
        assertThat(a.client.searchInvoices(new InvoiceSearchQuery("ISSUED", null, null, null, "DH", 0, 10)).total()).isZero();
        a.server.verify();

        Stub b = new Stub();
        b.server.expect(requestTo("http://sales/internal/sales/invoices/" + ID + "/print")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("<html>x</html>", MediaType.TEXT_HTML));
        PrintedInvoice printed = b.client.printInvoice(ID);
        assertThat(new String(printed.body(), StandardCharsets.UTF_8)).isEqualTo("<html>x</html>");
        b.server.verify();

        Stub c = new Stub();
        c.server.expect(requestTo("http://sales/internal/sales/orders/" + ORDER + "/invoice")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"clientRequestId\":\"k\"}"))
                .andRespond(withSuccess("{\"id\":\"" + ID + "\",\"status\":\"ISSUED\",\"unknownField\":1}",
                        MediaType.APPLICATION_JSON));
        assertThat(c.client.issueInvoice(ORDER, new lms.api.sales.dto.SalesInvoiceDtos.IssueInvoiceRequest("k")).id())
                .isEqualTo(ID);
        c.server.verify();

        Stub d = new Stub();
        d.server.expect(requestTo("http://sales/internal/sales/invoice-settings")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"complete\":false,\"version\":0,\"templateCode\":\"1\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(d.client.getInvoiceSettings().complete()).isFalse();
        d.server.verify();
    }
}
