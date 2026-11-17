package lms.system.cms.application;

/** Render Markdown thành HTML an toàn để nhúng thẳng vào trang (HTML thô bị escape, URL lạ bị bỏ). */
public interface MarkdownRendererPort {

    String toHtml(String markdown);
}
