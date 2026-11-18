package lms.api.sales.client.http;

import java.util.List;
import java.util.UUID;
import lms.api.sales.client.SalesInvoiceClient;
import lms.api.sales.dto.SalesDtos.SalesPage;
import lms.api.sales.dto.SalesInvoiceDtos.CancelInvoiceRequest;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceRequestView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSearchQuery;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSettingsView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSummaryView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceView;
import lms.api.sales.dto.SalesInvoiceDtos.IssueInvoiceRequest;
import lms.api.sales.dto.SalesInvoiceDtos.PrintedInvoice;
import lms.api.sales.dto.SalesInvoiceDtos.ReplaceInvoiceRequest;
import lms.api.sales.dto.SalesInvoiceDtos.SaveInvoiceRequestRequest;
import lms.api.sales.dto.SalesInvoiceDtos.UpdateInvoiceSettingsRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Gọi hóa đơn của sales-service. Token, tenant và correlation id do RestClient chuyển tiếp; lỗi 4xx chuyển nguyên. */
public class HttpSalesInvoiceClient implements SalesInvoiceClient {

    private static final String BASE = "/internal/sales";
    private static final ParameterizedTypeReference<SalesPage<InvoiceSummaryView>> INVOICE_PAGE =
            new ParameterizedTypeReference<>() { };

    private final RestClient client;

    public HttpSalesInvoiceClient(RestClient client) {
        this.client = client;
    }

    private static void param(UriBuilder b, String name, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return;
        }
        b.queryParam(name, value);
    }

    @Override
    public SalesPage<InvoiceSummaryView> searchInvoices(InvoiceSearchQuery q) {
        SalesPage<InvoiceSummaryView> page = client.get().uri(b -> {
            b.path(BASE + "/invoices");
            param(b, "status", q.status());
            param(b, "kind", q.kind());
            param(b, "from", q.from());
            param(b, "to", q.to());
            param(b, "keyword", q.keyword());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(INVOICE_PAGE);
        return page == null ? new SalesPage<>(List.of(), 0, 0, 0) : page;
    }

    @Override
    public InvoiceView getInvoice(UUID id) {
        return client.get().uri(BASE + "/invoices/{id}", id).retrieve().body(InvoiceView.class);
    }

    @Override
    public PrintedInvoice printInvoice(UUID id) {
        var entity = client.get().uri(BASE + "/invoices/{id}/print", id).accept(org.springframework.http.MediaType.TEXT_HTML)
                .retrieve().toEntity(byte[].class);
        String type = entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        return new PrintedInvoice(type, entity.getBody() == null ? new byte[0] : entity.getBody());
    }

    @Override
    public InvoiceView issueInvoice(UUID orderId, IssueInvoiceRequest request) {
        return client.post().uri(BASE + "/orders/{orderId}/invoice", orderId).body(request).retrieve()
                .body(InvoiceView.class);
    }

    @Override
    public InvoiceView cancelInvoice(UUID id, CancelInvoiceRequest request) {
        return client.post().uri(BASE + "/invoices/{id}/cancellation", id).body(request).retrieve()
                .body(InvoiceView.class);
    }

    @Override
    public InvoiceView replaceInvoice(UUID id, ReplaceInvoiceRequest request) {
        return client.post().uri(BASE + "/invoices/{id}/replacement", id).body(request).retrieve()
                .body(InvoiceView.class);
    }

    @Override
    public InvoiceRequestView getInvoiceRequest(UUID orderId) {
        return client.get().uri(BASE + "/orders/{orderId}/invoice-request", orderId).retrieve()
                .body(InvoiceRequestView.class);
    }

    @Override
    public InvoiceRequestView saveInvoiceRequest(UUID orderId, SaveInvoiceRequestRequest request) {
        return client.put().uri(BASE + "/orders/{orderId}/invoice-request", orderId).body(request).retrieve()
                .body(InvoiceRequestView.class);
    }

    @Override
    public InvoiceSettingsView getInvoiceSettings() {
        return client.get().uri(BASE + "/invoice-settings").retrieve().body(InvoiceSettingsView.class);
    }

    @Override
    public InvoiceSettingsView updateInvoiceSettings(UpdateInvoiceSettingsRequest request) {
        return client.put().uri(BASE + "/invoice-settings").body(request).retrieve().body(InvoiceSettingsView.class);
    }
}
