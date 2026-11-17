package lms.api.sales.web;

import jakarta.validation.Valid;
import java.util.UUID;
import lms.api.sales.client.SalesClient;
import lms.api.sales.dto.SalesDtos.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liên hệ của khách gửi cửa hàng (ADR-0015): nhân viên tìm, xem, xem liên hệ đầy đủ, đổi trạng thái, ẩn danh. Chuyển
 * nguyên sang sales-service (kiểm khóa, module, tenant ở đó); lms-api chỉ xác thực, lỗi 4xx chuyển nguyên.
 */
@RestController
@RequestMapping("/api/sales/contact-requests")
public class SalesContactRequestController {

    private final SalesClient sales;

    public SalesContactRequestController(SalesClient sales) {
        this.sales = sales;
    }

    @GetMapping
    public SalesPage<ShopContactSummaryView> search(ShopContactSearchQuery query) {
        return sales.searchContactRequests(query);
    }

    @GetMapping("/{id}")
    public ShopContactDetailView get(@PathVariable UUID id) {
        return sales.getContactRequest(id);
    }

    @GetMapping("/{id}/contact")
    public ShopContactContactView getContact(@PathVariable UUID id) {
        return sales.getContactRequestContact(id);
    }

    @PutMapping("/{id}/status")
    public ShopContactDetailView updateStatus(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateShopContactStatusRequest body) {
        return sales.updateContactRequestStatus(id, body);
    }

    @PostMapping("/{id}/anonymization")
    public ShopContactDetailView anonymize(@PathVariable UUID id) {
        return sales.anonymizeContactRequest(id);
    }
}
