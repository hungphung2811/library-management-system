package lms.support.notification.domain;

/** Mẫu nội dung: tiêu đề (email) + thân; source DEFAULT (classpath) hoặc TENANT (ghi đè), kèm phiên bản. */
public record MessageTemplate(String subject, String body, String source, int version) {

    public static final String DEFAULT = "DEFAULT";
    public static final String TENANT = "TENANT";

    public MessageTemplate {
        if (subject == null || subject.isBlank()) throw new InvalidTemplateException("Tiêu đề không được trống");
        if (body == null || body.isBlank()) throw new InvalidTemplateException("Nội dung không được trống");
    }
}
