package lms.sales.invoicing.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lms.sales.invoicing.application.InvoiceSettingsUseCases;
import lms.sales.invoicing.application.InvoiceSettingsView;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cấu hình hóa đơn theo tenant: người bán (tên pháp lý, MST, địa chỉ), mẫu số, ký hiệu, tự phát hành. KHÔNG cache
 * (use case phát hành đọc trực tiếp). Khóa quyền: InvoiceSettingsController/get, InvoiceSettingsController/update.
 */
@RestController
@RequestMapping("/internal/sales/invoice-settings")
@RequiresModule(TenantModule.SALES)
public class InvoiceSettingsController {

    public record UpdateInvoiceSettingsRequest(@NotBlank @Size(max = 255) String sellerLegalName,
                                               @NotBlank @Size(max = 14) String sellerTaxCode,
                                               @NotBlank @Size(max = 500) String sellerAddress,
                                               @Size(max = 30) String sellerPhone,
                                               @Size(max = 255) String sellerEmail,
                                               @NotBlank @Size(max = 6) String templateCode,
                                               @NotBlank @Size(max = 8) String series,
                                               @NotNull Boolean autoIssueOnCompletion, @NotNull Long version) {
    }

    private final InvoiceSettingsUseCases useCases;

    public InvoiceSettingsController(InvoiceSettingsUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping
    public InvoiceSettingsView get() {
        return useCases.get(RequestSupport.tenant());
    }

    @PutMapping
    public InvoiceSettingsView update(@Valid @RequestBody UpdateInvoiceSettingsRequest r,
                                      @AuthenticationPrincipal Jwt jwt) {
        return useCases.update(new InvoiceSettingsUseCases.UpdateCommand(RequestSupport.tenant(),
                RequestSupport.user(jwt), r.sellerLegalName(), r.sellerTaxCode(), r.sellerAddress(), r.sellerPhone(),
                r.sellerEmail(), r.templateCode(), r.series(), r.autoIssueOnCompletion(), r.version()));
    }
}
