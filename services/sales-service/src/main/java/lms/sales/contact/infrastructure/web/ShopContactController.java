package lms.sales.contact.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lms.sales.contact.application.SubmitShopContactUseCase;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khách đã đăng nhập gửi liên hệ tới cửa hàng (khóa ShopContactController/create, gán cho role Khách hàng). Người
 * gửi và tenant lấy từ token; body không có userId/tenantId/status (trường lạ bị bỏ qua). Trả 201 không thân, kể cả
 * khi trùng nội dung (R4) để không phân biệt "tạo mới" với "trùng". Không có @StorefrontAccessible: token svc bị 403.
 */
@RestController
@RequestMapping("/internal/sales/shop/contact-requests")
@RequiresModule(TenantModule.SALES)
public class ShopContactController {

    /** Giới hạn ở đây chỉ chặn thô (đếm UTF-16); độ dài chính xác theo code point do domain kiểm. */
    public record CreateContactRequest(@NotNull ContactTopic topic, @NotBlank @Size(max = 400) String fullName,
                                       @NotBlank @Size(max = 1000) String email, @Size(max = 100) String phone,
                                       @NotBlank @Size(max = 8000) String message, Boolean consent) {
        @Override
        public String toString() { // dữ liệu cá nhân: không in
            return "CreateContactRequest[***]";
        }
    }

    private final SubmitShopContactUseCase submit;

    public ShopContactController(SubmitShopContactUseCase submit) {
        this.submit = submit;
    }

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody CreateContactRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        submit.execute(new SubmitShopContactUseCase.Command(RequestSupport.tenant(), RequestSupport.user(jwt),
                request.topic(), request.fullName(), request.email(), request.phone(), request.message(),
                request.consent()));
        return ResponseEntity.status(201).header("Cache-Control", "no-store").build();
    }
}
