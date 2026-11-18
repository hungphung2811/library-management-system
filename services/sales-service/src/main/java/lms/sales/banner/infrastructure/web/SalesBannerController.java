package lms.sales.banner.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.banner.application.BannerAdminUseCases;
import lms.sales.banner.application.BannerAdminView;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản trị banner trang chủ cửa hàng (ADR-0019 2.5). Khóa SalesBannerController/{list,get,create,update,delete}.
 * KHÔNG {@code @StorefrontAccessible}: token dịch vụ storefront bị 403. Id của tenant khác trả 404 BANNER_NOT_FOUND.
 * Controller chỉ chặn thô kích thước; luật nội dung ở domain (INVALID_BANNER).
 */
@RestController
@RequestMapping("/internal/sales/banners")
@RequiresModule(TenantModule.SALES)
public class SalesBannerController {

    public record SaveBannerRequest(@Size(max = 1000) String title, @Size(max = 1000) String subtitle,
                                    @Size(max = 1000) String linkPath, @Min(0) @Max(100000) Integer sortOrder,
                                    Boolean active, Instant startsAt, Instant endsAt) {
        BannerAdminUseCases.SaveCommand toCommand() {
            return new BannerAdminUseCases.SaveCommand(title, subtitle, linkPath, sortOrder, active, startsAt,
                    endsAt);
        }
    }

    /** PUT thay toàn bộ trường, bắt buộc version. */
    public record UpdateBannerRequest(@Size(max = 1000) String title, @Size(max = 1000) String subtitle,
                                      @Size(max = 1000) String linkPath, @Min(0) @Max(100000) Integer sortOrder,
                                      Boolean active, Instant startsAt, Instant endsAt,
                                      @NotNull @Min(0) Long version) {
        BannerAdminUseCases.SaveCommand toCommand() {
            return new BannerAdminUseCases.SaveCommand(title, subtitle, linkPath, sortOrder, active, startsAt,
                    endsAt);
        }
    }

    private final BannerAdminUseCases banners;

    public SalesBannerController(BannerAdminUseCases banners) {
        this.banners = banners;
    }

    @GetMapping
    public List<BannerAdminView> list() {
        return banners.list(RequestSupport.tenant());
    }

    @GetMapping("/{id}")
    public BannerAdminView get(@PathVariable UUID id) {
        return banners.get(RequestSupport.tenant(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BannerAdminView create(@Valid @RequestBody SaveBannerRequest body, @AuthenticationPrincipal Jwt jwt) {
        return banners.create(RequestSupport.tenant(), RequestSupport.user(jwt), body.toCommand());
    }

    @PutMapping("/{id}")
    public BannerAdminView update(@PathVariable UUID id, @Valid @RequestBody UpdateBannerRequest body,
                                  @AuthenticationPrincipal Jwt jwt) {
        return banners.update(RequestSupport.tenant(), RequestSupport.user(jwt), id, body.version(),
                body.toCommand());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        banners.delete(RequestSupport.tenant(), RequestSupport.user(jwt), id);
    }
}
