package lms.system.cms.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.system.cms.application.CmsPageRepositoryPort;
import lms.system.cms.application.ContactRequestRepositoryPort;
import lms.system.cms.application.PublishedCmsPagesUseCase;
import lms.system.cms.domain.BusinessType;
import lms.system.cms.domain.CmsPage;
import lms.system.cms.domain.CmsPageStatus;
import lms.system.cms.domain.ContactRequest;
import lms.system.cms.domain.ContactStatus;

public final class CmsResponses {

    private CmsResponses() {
    }

    // ---------- công khai (chỉ cột đã xuất bản) ----------

    public record PublicPageSummary(String slug, String title, int sortOrder) {
        static PublicPageSummary from(PublishedCmsPagesUseCase.Summary s) {
            return new PublicPageSummary(s.slug(), s.title(), s.sortOrder());
        }
    }

    public record PublicPage(String slug, String title, String bodyHtml, Instant publishedAt) {
        static PublicPage from(PublishedCmsPagesUseCase.Published p) {
            return new PublicPage(p.slug(), p.title(), p.bodyHtml(), p.publishedAt());
        }
    }

    // ---------- quản trị trang ----------

    public record PageSummary(UUID id, String slug, String title, CmsPageStatus status, int sortOrder,
                              Instant publishedAt, long version, Instant updatedAt) {
        static PageSummary from(CmsPageRepositoryPort.AdminSummary p) {
            return new PageSummary(p.id(), p.slug(), p.title(), p.status(), p.sortOrder(), p.publishedAt(),
                    p.version(), p.updatedAt());
        }
    }

    public record PageDetail(UUID id, String slug, String title, String bodyMarkdown, CmsPageStatus status,
                             String publishedTitle, String publishedBodyMarkdown, Instant publishedAt, int sortOrder,
                             long version, Instant createdAt, Instant updatedAt, UUID updatedBy) {
        static PageDetail from(CmsPage p) {
            return new PageDetail(p.id(), p.slug(), p.title(), p.bodyMarkdown(), p.status(), p.publishedTitle(),
                    p.publishedBodyMarkdown(), p.publishedAt(), p.sortOrder(), p.version(), p.createdAt(),
                    p.updatedAt(), p.updatedBy());
        }
    }

    // ---------- quản trị yêu cầu liên hệ (có dữ liệu cá nhân, null sau khi ẩn danh) ----------

    public record ContactSummary(UUID id, String fullName, String email, String organization,
                                 BusinessType businessType, ContactStatus status, Instant createdAt,
                                 Instant anonymizedAt) {
        static ContactSummary from(ContactRequest r) {
            return new ContactSummary(r.id(), r.fullName(), r.email(), r.organization(), r.businessType(),
                    r.status(), r.createdAt(), r.anonymizedAt());
        }
    }

    public record ContactDetail(UUID id, String fullName, String email, String phone, String organization,
                                BusinessType businessType, String message, Instant consentAt, ContactStatus status,
                                long version, Instant createdAt, UUID handledBy, Instant handledAt,
                                Instant anonymizedAt) {
        static ContactDetail from(ContactRequest r) {
            return new ContactDetail(r.id(), r.fullName(), r.email(), r.phone(), r.organization(), r.businessType(),
                    r.message(), r.consentAt(), r.status(), r.version(), r.createdAt(), r.handledBy(), r.handledAt(),
                    r.anonymizedAt());
        }
    }

    public record ContactPage(List<ContactSummary> items, int page, int size, long total) {
        static ContactPage from(ContactRequestRepositoryPort.Page<ContactRequest> page) {
            return new ContactPage(page.items().stream().map(ContactSummary::from).toList(), page.page(),
                    page.size(), page.total());
        }
    }
}
