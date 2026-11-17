package lms.api.system.web;

import lms.api.system.client.CmsClient;
import lms.api.system.dto.CmsDtos.CmsPageSummary;
import lms.api.system.dto.CmsDtos.CmsPageView;
import lms.api.system.dto.CmsDtos.PublishCmsPageRequest;
import lms.api.system.dto.CmsDtos.CreateCmsPageRequest;
import lms.api.system.dto.CmsDtos.UpdateCmsPageRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Quản trị trang CMS. system-service tự kiểm (platform admin) trên token được chuyển tiếp; lms-api không kiểm. */
@RestController
@RequestMapping("/api/cms/pages")
public class CmsPageAdminController {

    private final CmsClient cms;

    public CmsPageAdminController(CmsClient cms) {
        this.cms = cms;
    }

    @GetMapping
    public List<CmsPageSummary> list() {
        return cms.listPages();
    }

    @GetMapping("/{id}")
    public CmsPageView get(@PathVariable UUID id) {
        return cms.getPage(id);
    }

    @PostMapping
    public ResponseEntity<CmsPageView> create(@Valid @RequestBody CreateCmsPageRequest request) {
        CmsPageView created = cms.createPage(request);
        return ResponseEntity.created(URI.create("/api/cms/pages/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public CmsPageView update(@PathVariable UUID id, @Valid @RequestBody UpdateCmsPageRequest request) {
        return cms.updatePage(id, request);
    }

    /** Xuất bản đúng phiên bản đã xem: chép bản làm việc sang bản công khai; version lệch thì 409 CMS_VERSION_CONFLICT. */
    @PostMapping("/{id}/publication")
    public CmsPageView publish(@PathVariable UUID id, @Valid @RequestBody PublishCmsPageRequest request) {
        return cms.publishPage(id, request);
    }

    /** Gỡ xuất bản: xóa bản công khai, giữ bản làm việc. */
    @DeleteMapping("/{id}/publication")
    public CmsPageView unpublish(@PathVariable UUID id) {
        return cms.unpublishPage(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        cms.deletePage(id);
        return ResponseEntity.noContent().build();
    }
}
