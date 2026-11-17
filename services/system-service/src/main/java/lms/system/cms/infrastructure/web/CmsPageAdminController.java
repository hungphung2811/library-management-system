package lms.system.cms.infrastructure.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import lms.system.cms.application.ManageCmsPagesUseCase;
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

/** Quản trị trang nội dung: chỉ platform admin (không dùng khóa quyền động), không cần tenant. */
@PlatformAdminOnly
@RestController
@RequestMapping("/internal/cms/pages")
public class CmsPageAdminController {

    private final ManageCmsPagesUseCase pages;

    public CmsPageAdminController(ManageCmsPagesUseCase pages) {
        this.pages = pages;
    }

    /** Token (hoặc dev không có JWT) không có sub hợp lệ thì người sửa là null. */
    static UUID actor(Jwt jwt) {
        return jwt == null ? null : CurrentUser.from(jwt).userId();
    }

    @GetMapping
    public List<CmsResponses.PageSummary> list() {
        return pages.list().stream().map(CmsResponses.PageSummary::from).toList();
    }

    @GetMapping("/{id}")
    public CmsResponses.PageDetail get(@PathVariable UUID id) {
        return CmsResponses.PageDetail.from(pages.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CmsResponses.PageDetail create(@Valid @RequestBody CmsRequests.CreatePage request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return CmsResponses.PageDetail.from(pages.create(request.toCommand(actor(jwt))));
    }

    @PutMapping("/{id}")
    public CmsResponses.PageDetail update(@PathVariable UUID id, @Valid @RequestBody CmsRequests.UpdatePage request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return CmsResponses.PageDetail.from(pages.update(request.toCommand(id, actor(jwt))));
    }

    /** Xuất bản đúng phiên bản {@code version} đã xem; lệch thì 409 CMS_VERSION_CONFLICT. */
    @PostMapping("/{id}/publication")
    public CmsResponses.PageDetail publish(@PathVariable UUID id, @Valid @RequestBody CmsRequests.PublishPage request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return CmsResponses.PageDetail.from(pages.publish(id, request.version(), actor(jwt)));
    }

    @DeleteMapping("/{id}/publication")
    public CmsResponses.PageDetail unpublish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return CmsResponses.PageDetail.from(pages.unpublish(id, actor(jwt)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        pages.delete(id);
    }
}
