package lms.system.cms.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.CmsPage;
import org.springframework.transaction.annotation.Transactional;

/** Quản trị trang nội dung (platform admin). Mỗi thao tác ghi một DB (system_db). */
public class ManageCmsPagesUseCase {

    private final CmsPageRepositoryPort repository;
    private final Clock clock;

    public ManageCmsPagesUseCase(CmsPageRepositoryPort repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record CreateCommand(String slug, String title, String bodyMarkdown, int sortOrder, UUID by) {
    }

    public record UpdateCommand(UUID id, String title, String bodyMarkdown, int sortOrder, long version, UUID by) {
    }

    @Transactional(readOnly = true)
    public List<CmsPageRepositoryPort.AdminSummary> list() {
        return repository.findAllSummaries();
    }

    @Transactional(readOnly = true)
    public CmsPage get(UUID id) {
        return find(id);
    }

    @Transactional
    public CmsPage create(CreateCommand command) {
        CmsPage page = CmsPage.create(UUID.randomUUID(), command.slug(), command.title(), command.bodyMarkdown(),
                command.sortOrder(), command.by(), clock.instant());
        if (repository.findBySlug(page.slug()).isPresent()) {
            throw duplicated();
        }
        return repository.save(page);
    }

    @Transactional
    public CmsPage update(UpdateCommand command) {
        CmsPage current = find(command.id());
        current.requireVersion(command.version());
        return repository.save(current.edit(command.title(), command.bodyMarkdown(), command.sortOrder(),
                command.by(), clock.instant()));
    }

    /** Xuất bản đúng phiên bản admin đã xem ({@code version}): bản sửa sau đó không bị đẩy ra công khai nhầm. */
    @Transactional
    public CmsPage publish(UUID id, long version, UUID by) {
        CmsPage current = find(id);
        current.requireVersion(version);
        return repository.save(current.publish(by, clock.instant()));
    }

    @Transactional
    public CmsPage unpublish(UUID id, UUID by) {
        CmsPage current = find(id);
        return current.isPublished() ? repository.save(current.unpublish(by, clock.instant())) : current;
    }

    @Transactional
    public void delete(UUID id) {
        find(id);
        repository.deleteById(id);
    }

    private CmsPage find(UUID id) {
        return repository.findById(id).orElseThrow(
                () -> new CmsException(CmsErrorCode.CMS_PAGE_NOT_FOUND, "Không tìm thấy trang"));
    }

    private static CmsException duplicated() {
        return new CmsException(CmsErrorCode.CMS_SLUG_DUPLICATED, "Slug đã được dùng");
    }
}
