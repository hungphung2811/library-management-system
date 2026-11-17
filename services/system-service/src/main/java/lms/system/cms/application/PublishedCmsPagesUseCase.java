package lms.system.cms.application;

import java.time.Instant;
import java.util.List;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.CmsPage;

/**
 * Đọc nội dung đã xuất bản cho trang công khai. Chỉ dùng cột published_*: bản nháp/bản đang sửa không bao giờ lộ.
 * HTML do {@link MarkdownRendererPort} render lúc đọc.
 */
public class PublishedCmsPagesUseCase {

    private final CmsPageRepositoryPort repository;
    private final MarkdownRendererPort renderer;

    public PublishedCmsPagesUseCase(CmsPageRepositoryPort repository, MarkdownRendererPort renderer) {
        this.repository = repository;
        this.renderer = renderer;
    }

    public record Summary(String slug, String title, int sortOrder) {
    }

    public record Published(String slug, String title, String bodyHtml, Instant publishedAt) {
    }

    public List<Summary> list() {
        return repository.findPublishedSummaries().stream()
                .map(p -> new Summary(p.slug(), p.title(), p.sortOrder())).toList();
    }

    /**
     * KHÔNG bọc giao dịch: render có thể tốn hàng giây, không được giữ kết nối DB (pool nhỏ). Việc đọc chạy trong giao
     * dịch ngắn riêng của adapter, render nằm ngoài.
     */
    public Published get(String slug) {
        CmsPage page = repository.findPublishedBySlug(slug).orElseThrow(
                () -> new CmsException(CmsErrorCode.CMS_PAGE_NOT_FOUND, "Không tìm thấy trang"));
        return new Published(page.slug(), page.publishedTitle(), renderer.toHtml(page.publishedBodyMarkdown()),
                page.publishedAt());
    }
}
