package lms.sales.contact.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lms.sales.contact.application.ShopContactQueryUseCases;
import lms.sales.contact.application.ShopContactRepository;
import lms.sales.contact.application.ShopContactWorkflowUseCases;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhân viên xử lý liên hệ của khách (ADR-0015 mục 2.5; mặc định chỉ role Quản lý cửa hàng có khóa): search, get,
 * getContact (liên hệ đầy đủ, khóa riêng), updateStatus, anonymize. Id của tenant khác trả 404 SHOP_CONTACT_NOT_FOUND.
 */
@RestController
@RequestMapping("/internal/sales/contact-requests")
@RequiresModule(TenantModule.SALES)
public class SalesContactController {

    /** from bao gồm, to loại trừ, theo thời điểm tạo (ISO-8601); page bắt đầu từ 0. */
    public record ContactSearchQuery(ContactStatus status, ContactTopic topic, Instant from, Instant to,
                                     Integer page, Integer size) {
    }

    public record UpdateStatusRequest(@NotNull ContactStatus status, @NotNull @Min(0) Long version) {
    }

    private final ShopContactQueryUseCases queries;
    private final ShopContactWorkflowUseCases workflow;

    public SalesContactController(ShopContactQueryUseCases queries, ShopContactWorkflowUseCases workflow) {
        this.queries = queries;
        this.workflow = workflow;
    }

    @GetMapping
    public PageResult<ShopContactViews.SummaryView> search(ContactSearchQuery query) {
        return queries.search(RequestSupport.tenant(), new ShopContactRepository.SearchQuery(query.status(),
                query.topic(), query.from(), query.to(), RequestSupport.page(query.page()),
                RequestSupport.size(query.size()))).map(ShopContactViews.SummaryView::from);
    }

    @GetMapping("/{id}")
    public ShopContactViews.DetailView get(@PathVariable UUID id) {
        return ShopContactViews.DetailView.from(queries.get(RequestSupport.tenant(), id));
    }

    @GetMapping("/{id}/contact")
    public ShopContactViews.ContactView getContact(@PathVariable UUID id) {
        return ShopContactViews.ContactView.from(queries.contact(RequestSupport.tenant(), id));
    }

    @PutMapping("/{id}/status")
    public ShopContactViews.DetailView updateStatus(@PathVariable UUID id,
                                                    @Valid @RequestBody UpdateStatusRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return ShopContactViews.DetailView.from(workflow.changeStatus(RequestSupport.tenant(),
                RequestSupport.user(jwt), id, body.status(), body.version()));
    }

    @PostMapping("/{id}/anonymization")
    public ShopContactViews.DetailView anonymize(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ShopContactViews.DetailView.from(workflow.anonymize(RequestSupport.tenant(),
                RequestSupport.user(jwt), id));
    }
}
