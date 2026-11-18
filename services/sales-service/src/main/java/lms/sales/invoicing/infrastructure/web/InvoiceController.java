package lms.sales.invoicing.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceQueryUseCases;
import lms.sales.invoicing.application.InvoiceSummary;
import lms.sales.invoicing.application.InvoiceUseCases;
import lms.sales.invoicing.application.InvoiceView;
import lms.sales.invoicing.domain.InvoiceKind;
import lms.sales.invoicing.domain.InvoiceStatus;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hóa đơn VAT nội bộ (ADR-0030 gói A). Khóa quyền: InvoiceController/search, get, print, issue, cancel, replace.
 * Hóa đơn nội bộ CHƯA có giá trị pháp lý của hóa đơn điện tử. Đổi tên controller/method là đổi khóa quyền.
 */
@RestController
@RequestMapping("/internal/sales")
@RequiresModule(TenantModule.SALES)
public class InvoiceController {

    /** {@code from} bao gồm, {@code to} loại trừ, theo thời điểm tạo (ISO-8601); {@code page} bắt đầu từ 0. */
    public record InvoiceSearchQuery(InvoiceStatus status, InvoiceKind kind, Instant from, Instant to,
                                     @Size(max = 100) String keyword, Integer page, Integer size) {
    }

    public record IssueInvoiceRequest(@NotBlank @Size(max = 100) String clientRequestId) {
    }

    public record CancelInvoiceRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** {@code buyer} vắng thì giữ người mua của hóa đơn gốc. */
    public record ReplaceInvoiceRequest(@NotBlank @Size(max = 500) String reason, @Valid BuyerRequest buyer,
                                        @NotBlank @Size(max = 100) String clientRequestId) {
    }

    private final InvoiceQueryUseCases queries;
    private final InvoiceUseCases useCases;

    public InvoiceController(InvoiceQueryUseCases queries, InvoiceUseCases useCases) {
        this.queries = queries;
        this.useCases = useCases;
    }

    @GetMapping("/invoices")
    public PageResult<InvoiceSummary> search(@Valid InvoiceSearchQuery query) {
        return queries.search(RequestSupport.tenant(), new InvoiceQueryUseCases.SearchQuery(query.status(),
                query.kind(), query.from(), query.to(), query.keyword(), RequestSupport.page(query.page()),
                RequestSupport.size(query.size())));
    }

    @GetMapping("/invoices/{id}")
    public InvoiceView get(@PathVariable UUID id) {
        return queries.get(RequestSupport.tenant(), id);
    }

    /**
     * Bản in HTML (text/html, in được). Dữ liệu đã thoát HTML; không script; header CSP chặn mọi tài nguyên ngoài và
     * không cho nhúng; không lưu cache (có thông tin người mua).
     */
    @GetMapping(value = "/invoices/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> print(@PathVariable UUID id) {
        var rendered = queries.print(RequestSupport.tenant(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, rendered.contentType())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(rendered.body());
    }

    @PostMapping("/orders/{orderId}/invoice")
    public ResponseEntity<InvoiceView> issue(@PathVariable UUID orderId,
                                             @Valid @RequestBody IssueInvoiceRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        var invoice = useCases.issue(new InvoiceUseCases.IssueCommand(RequestSupport.tenant(), orderId,
                RequestSupport.user(jwt), RequestSupport.clientRequestId(request.clientRequestId())));
        return ResponseEntity.created(URI.create("/internal/sales/invoices/" + invoice.id())).body(invoice);
    }

    @PostMapping("/invoices/{id}/cancellation")
    public InvoiceView cancel(@PathVariable UUID id, @Valid @RequestBody CancelInvoiceRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return useCases.cancel(new InvoiceUseCases.CancelCommand(RequestSupport.tenant(), id,
                RequestSupport.user(jwt), request.reason()));
    }

    @PostMapping("/invoices/{id}/replacement")
    public ResponseEntity<InvoiceView> replace(@PathVariable UUID id,
                                               @Valid @RequestBody ReplaceInvoiceRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        var invoice = useCases.replace(new InvoiceUseCases.ReplaceCommand(RequestSupport.tenant(), id,
                RequestSupport.user(jwt), request.reason(), request.buyer() == null ? null : request.buyer().toInput(),
                RequestSupport.clientRequestId(request.clientRequestId())));
        return ResponseEntity.created(URI.create("/internal/sales/invoices/" + invoice.id())).body(invoice);
    }
}
