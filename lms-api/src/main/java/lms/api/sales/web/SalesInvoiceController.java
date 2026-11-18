package lms.api.sales.web;

import jakarta.validation.Valid;
import java.net.URI;
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
import lms.api.sales.dto.SalesInvoiceDtos.ReplaceInvoiceRequest;
import lms.api.sales.dto.SalesInvoiceDtos.SaveInvoiceRequestRequest;
import lms.api.sales.dto.SalesInvoiceDtos.UpdateInvoiceSettingsRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hóa đơn VAT nội bộ (ADR-0030 gói A): chuyển nguyên sang /internal/sales/** (kiểm khóa, module, tenant ở sales-service;
 * lms-api chỉ xác thực, lỗi 4xx chuyển nguyên). Hóa đơn nội bộ CHƯA có giá trị pháp lý của hóa đơn điện tử. Bản in luôn
 * được trả với Content-Type text/html và header CSP chặn script/tài nguyên ngoài, bất kể sales-service trả gì.
 */
@RestController
@RequestMapping("/api/sales")
public class SalesInvoiceController {

    private final SalesInvoiceClient invoices;

    public SalesInvoiceController(SalesInvoiceClient invoices) {
        this.invoices = invoices;
    }

    @GetMapping("/invoices")
    public SalesPage<InvoiceSummaryView> search(InvoiceSearchQuery query) {
        return invoices.searchInvoices(query);
    }

    @GetMapping("/invoices/{id}")
    public InvoiceView get(@PathVariable UUID id) {
        return invoices.getInvoice(id);
    }

    @GetMapping(value = "/invoices/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> print(@PathVariable UUID id) {
        var printed = invoices.printInvoice(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(printed.body());
    }

    @PostMapping("/orders/{orderId}/invoice")
    public ResponseEntity<InvoiceView> issue(@PathVariable UUID orderId,
                                             @Valid @RequestBody IssueInvoiceRequest request) {
        InvoiceView created = invoices.issueInvoice(orderId, request);
        return ResponseEntity.created(URI.create("/api/sales/invoices/" + created.id())).body(created);
    }

    @PostMapping("/invoices/{id}/cancellation")
    public InvoiceView cancel(@PathVariable UUID id, @Valid @RequestBody CancelInvoiceRequest request) {
        return invoices.cancelInvoice(id, request);
    }

    @PostMapping("/invoices/{id}/replacement")
    public ResponseEntity<InvoiceView> replace(@PathVariable UUID id,
                                               @Valid @RequestBody ReplaceInvoiceRequest request) {
        InvoiceView created = invoices.replaceInvoice(id, request);
        return ResponseEntity.created(URI.create("/api/sales/invoices/" + created.id())).body(created);
    }

    @GetMapping("/orders/{orderId}/invoice-request")
    public InvoiceRequestView getRequest(@PathVariable UUID orderId) {
        return invoices.getInvoiceRequest(orderId);
    }

    @PutMapping("/orders/{orderId}/invoice-request")
    public InvoiceRequestView saveRequest(@PathVariable UUID orderId,
                                          @Valid @RequestBody SaveInvoiceRequestRequest request) {
        return invoices.saveInvoiceRequest(orderId, request);
    }

    @GetMapping("/invoice-settings")
    public InvoiceSettingsView getSettings() {
        return invoices.getInvoiceSettings();
    }

    @PutMapping("/invoice-settings")
    public InvoiceSettingsView updateSettings(@Valid @RequestBody UpdateInvoiceSettingsRequest request) {
        return invoices.updateInvoiceSettings(request);
    }
}
