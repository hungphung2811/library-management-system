package lms.sales.stock.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import lms.cache.RequestCache;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.stock.application.SupplierUseCases;
import lms.sales.stock.domain.Supplier;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nhà cung cấp. Khóa: search, create, update. */
@RestController
@RequestMapping("/internal/sales/suppliers")
@RequiresModule(TenantModule.SALES)
public class SupplierController {

    /** active chỉ dùng khi sửa; bỏ trống = giữ nguyên (tạo mới luôn active). */
    public record SupplierRequest(@NotBlank @Size(max = 200) String name, @Size(max = 30) String phone,
                                  @Size(max = 200) String email, @Size(max = 500) String address,
                                  @Size(max = 1000) String note, Boolean active) {
    }

    public record SupplierSearchQuery(String keyword, Integer page, Integer size) {
    }

    public record SupplierResponse(UUID id, String name, String phone, String email, String address, String note,
                                   boolean active) {
        static SupplierResponse from(Supplier s) {
            return new SupplierResponse(s.id(), s.name(), s.phone(), s.email(), s.address(), s.note(), s.active());
        }
    }

    static final String CACHE_NAMESPACE = "sales.suppliers";
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** Khóa cache: mọi đầu vào làm đổi kết quả (từ khóa, trang, cỡ trang đã chuẩn hóa). */
    private record SearchKey(String keyword, int page, int size) {
    }

    private final SupplierUseCases suppliers;
    private final RequestCache cache;

    public SupplierController(SupplierUseCases suppliers, RequestCache cache) {
        this.suppliers = suppliers;
        this.cache = cache;
    }

    @GetMapping
    public PageResult<SupplierResponse> search(SupplierSearchQuery q) {
        var tenant = RequestSupport.tenant();
        int page = RequestSupport.page(q.page());
        int size = RequestSupport.size(q.size());
        return cache.get(CACHE_NAMESPACE, new SearchKey(q.keyword(), page, size), CACHE_TTL,
                new TypeReference<PageResult<SupplierResponse>>() { },
                () -> suppliers.search(tenant, q.keyword(), page, size).map(SupplierResponse::from));
    }

    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest r,
                                                   @AuthenticationPrincipal Jwt jwt) {
        Supplier s = suppliers.create(RequestSupport.tenant(), RequestSupport.user(jwt), data(r));
        cache.evictNamespace(CACHE_NAMESPACE);
        return ResponseEntity.created(URI.create("/internal/sales/suppliers/" + s.id()))
                .body(SupplierResponse.from(s));
    }

    @PutMapping("/{id}")
    public SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest r,
                                   @AuthenticationPrincipal Jwt jwt) {
        SupplierResponse saved = SupplierResponse.from(suppliers.update(RequestSupport.tenant(),
                RequestSupport.user(jwt), id, data(r), r.active()));
        cache.evictNamespace(CACHE_NAMESPACE);
        return saved;
    }

    private static SupplierUseCases.SupplierData data(SupplierRequest r) {
        return new SupplierUseCases.SupplierData(r.name(), r.phone(), r.email(), r.address(), r.note());
    }
}
