package lms.support.notification.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.application.NotificationConfigPorts.DefaultTemplatePort;
import lms.support.notification.application.NotificationConfigPorts.StoredTemplate;
import lms.support.notification.application.NotificationConfigPorts.TemplateStorePort;
import lms.support.notification.domain.InvalidTemplateException;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.TemplateRenderer;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mẫu nội dung (D6): mặc định trong classpath, tenant ghi đè bằng phiên bản mới (một phiên bản active). Lưu mẫu có
 * tham số ngoài danh sách của loại thì bị từ chối (TEMPLATE_PARAM_NOT_ALLOWED). Xem trước dùng dữ liệu mẫu, không gửi.
 */
public class NotificationTemplateUseCases {

    private static final int MAX_SUBJECT = 200;
    private static final int MAX_BODY = 5000;

    public record TemplateView(String type, String category, String channel, String source, int version,
                               String subject, String body, List<String> allowedParams) {
    }

    public record PreviewView(String subject, String body) {
    }

    private final TemplateStorePort store;
    private final DefaultTemplatePort defaults;
    private final TemplateResolver resolver;
    private final Clock clock;

    public NotificationTemplateUseCases(TemplateStorePort store, DefaultTemplatePort defaults,
                                        TemplateResolver resolver, Clock clock) {
        this.store = store;
        this.defaults = defaults;
        this.resolver = resolver;
        this.clock = clock;
    }

    /** Mẫu đang hiệu lực của mọi (loại x kênh). */
    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public List<TemplateView> list() {
        Map<String, StoredTemplate> overrides = new HashMap<>();
        for (StoredTemplate s : store.listActive()) overrides.put(s.type() + ":" + s.channel(), s);
        List<TemplateView> out = new ArrayList<>();
        for (NotificationType t : NotificationType.values()) {
            for (NotificationChannel c : NotificationChannel.values()) {
                StoredTemplate o = overrides.get(t + ":" + c);
                Optional<MessageTemplate> tpl = o != null ? Optional.of(o.template()) : defaults.find(t, c);
                tpl.ifPresent(m -> out.add(view(t, c, m)));
            }
        }
        return out;
    }

    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public Optional<TemplateView> get(NotificationType type, NotificationChannel channel) {
        return resolver.resolve(type, channel).map(m -> view(type, channel, m));
    }

    @Transactional(NotificationTransactions.MANAGER)
    public TemplateView save(NotificationType type, NotificationChannel channel, String subject, String body,
                             UUID by) {
        String s = subject == null ? "" : subject.trim();
        String b = body == null ? "" : body.trim();
        if (s.length() > MAX_SUBJECT) throw new InvalidTemplateException("Tiêu đề tối đa " + MAX_SUBJECT + " ký tự");
        if (b.length() > MAX_BODY) throw new InvalidTemplateException("Nội dung tối đa " + MAX_BODY + " ký tự");
        new MessageTemplate(s, b, MessageTemplate.TENANT, 0);
        TemplateRenderer.validate(type, s, b);
        MessageTemplate saved = store.saveNewVersion(type, channel, s, b, by, clock.instant());
        return view(type, channel, saved);
    }

    /** Dựng thử mẫu (đang hiệu lực hoặc bản nháp truyền vào) với dữ liệu giả. */
    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public PreviewView preview(NotificationType type, NotificationChannel channel, String subject, String body) {
        MessageTemplate tpl;
        if (subject != null && body != null) {
            TemplateRenderer.validate(type, subject, body);
            tpl = new MessageTemplate(subject, body, MessageTemplate.TENANT, 0);
        } else {
            tpl = resolver.resolve(type, channel).orElseThrow(() -> new InvalidTemplateException("Chưa có mẫu"));
        }
        MessageTemplate out = TemplateRenderer.render(tpl, sampleValues(type));
        return new PreviewView(out.subject(), out.body());
    }

    private static Map<String, String> sampleValues(NotificationType type) {
        Map<String, String> v = new HashMap<>();
        for (String p : type.allowedParams()) v.put(p, "[" + p + "]");
        v.put("recipient_name", "Nguyễn Văn A");
        v.put("tenant_name", "Hiệu sách Mẫu");
        return v;
    }

    private static TemplateView view(NotificationType t, NotificationChannel c, MessageTemplate m) {
        return new TemplateView(t.name(), t.category().name(), c.name(), m.source(), m.version(), m.subject(),
                m.body(), t.allowedParams());
    }
}
