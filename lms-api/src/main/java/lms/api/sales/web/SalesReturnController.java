package lms.api.sales.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lms.api.sales.client.SalesClient;
import lms.api.sales.dto.SalesDtos.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trả hàng (thường và quá hạn), hoàn tiền, nhật ký bán hàng. BFF không kiểm quyền (ADR-0005): sales-service kiểm khóa
 * theo từng endpoint ({@code SalesReturnController/create} và {@code /createLate} là hai khóa riêng).
 */
@RestController
@RequestMapping("/api/sales")
public class SalesReturnController {

    private final SalesClient sales;

    public SalesReturnController(SalesClient sales) {
        this.sales = sales;
    }

    @PostMapping("/returns")
    public ResponseEntity<ReturnView> create(@Valid @RequestBody CreateReturnRequest request) {
        ReturnView created = sales.createReturn(request);
        return ResponseEntity.created(URI.create("/api/sales/returns/" + created.id())).body(created);
    }

    @PostMapping("/returns/late")
    public ResponseEntity<ReturnView> createLate(@Valid @RequestBody CreateLateReturnRequest request) {
        ReturnView created = sales.createLateReturn(request);
        return ResponseEntity.created(URI.create("/api/sales/returns/" + created.id())).body(created);
    }

    @GetMapping("/returns/{id}")
    public ReturnView get(@PathVariable UUID id) {
        return sales.getReturn(id);
    }

    @GetMapping("/orders/{id}/returns")
    public List<ReturnView> listByOrder(@PathVariable UUID id) {
        return sales.listReturnsByOrder(id);
    }

    @GetMapping("/refunds")
    public SalesPage<RefundView> searchRefunds(RefundSearchQuery query) {
        return sales.searchRefunds(query);
    }

    @PostMapping("/refunds/{id}/completion")
    public RefundView markRefunded(@PathVariable UUID id, @Valid @RequestBody MarkRefundedRequest body) {
        return sales.markRefunded(id, body);
    }

    @GetMapping("/audit-log")
    public SalesPage<AuditLogEntryView> searchAuditLog(AuditLogQuery query) {
        return sales.searchAuditLog(query);
    }
}
