package lms.api.sales.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.api.sales.client.SalesClient;
import lms.api.sales.dto.SalesDtos.BannerAdminView;
import lms.api.sales.dto.SalesDtos.SaveBannerRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản trị banner trang chủ storefront (ADR-0019 B5). Chuyển nguyên sang /internal/sales/banners (kiểm khóa, module,
 * tenant, quy tắc trường ở sales); lms-api chỉ xác thực, lỗi 4xx chuyển nguyên.
 */
@RestController
@RequestMapping("/api/sales/banners")
public class SalesBannerController {

    private final SalesClient sales;

    public SalesBannerController(SalesClient sales) {
        this.sales = sales;
    }

    @GetMapping
    public List<BannerAdminView> list() {
        return sales.listBanners();
    }

    @GetMapping("/{id}")
    public BannerAdminView get(@PathVariable UUID id) {
        return sales.getBanner(id);
    }

    @PostMapping
    public ResponseEntity<BannerAdminView> create(@Valid @RequestBody SaveBannerRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sales.createBanner(body));
    }

    @PutMapping("/{id}")
    public BannerAdminView update(@PathVariable UUID id, @Valid @RequestBody SaveBannerRequest body) {
        return sales.updateBanner(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        sales.deleteBanner(id);
        return ResponseEntity.noContent().build();
    }
}
