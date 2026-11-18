package lms.support.notification.unit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lms.support.notification.application.DeliveryPort;
import lms.support.notification.application.DeliveryTransactions;
import lms.support.notification.application.DeliveryUseCases;
import lms.support.notification.application.DispatchNotificationsUseCase;
import lms.support.notification.application.EnqueueNotificationUseCase;
import lms.support.notification.application.NotificationConfigPorts.ChannelSetting;
import lms.support.notification.application.NotificationConfigPorts.ChannelSettingsPort;
import lms.support.notification.application.NotificationConfigPorts.Preference;
import lms.support.notification.application.NotificationConfigPorts.PreferencesPort;
import lms.support.notification.application.NotificationConfigPorts.StoredTemplate;
import lms.support.notification.application.NotificationConfigPorts.TemplateStorePort;
import lms.support.notification.application.NotificationConfigPorts.TenantRegistryPort;
import lms.support.notification.application.NotificationProvider;
import lms.support.notification.application.NotificationProviderRegistry;
import lms.support.notification.application.NotificationSettingsUseCases;
import lms.support.notification.application.NotificationTemplateUseCases;
import lms.support.notification.application.RecipientDirectoryPort;
import lms.support.notification.application.TemplateResolver;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.RetryPolicy;
import lms.support.notification.infrastructure.email.ClasspathDefaultTemplates;
import lms.tenant.TenantContext;

/**
 * Bộ dựng nhanh các use case thông báo trên bản giả trong bộ nhớ (cách ly theo TenantContext như @TenantId/RLS) để test
 * đơn vị khử trùng, thử lại, giới hạn tốc độ, tùy chọn, mẫu. Dùng mẫu mặc định THẬT trong classpath.
 */
final class NotificationKit {

    /** Đồng hồ chỉnh tay. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-19T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Provider giả: đếm số thư, ghi lại người nhận, có thể ném lỗi theo kịch bản. */
    static final class FakeProvider implements NotificationProvider {
        final List<String> sentTo = new ArrayList<>();
        final List<RenderedMessage> messages = new ArrayList<>();
        final List<RuntimeException> failures = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public NotificationChannel channel() { return NotificationChannel.EMAIL; }

        @Override
        public SendResult send(RenderedMessage message, ResolvedAddress address) {
            calls.incrementAndGet();
            if (!failures.isEmpty()) throw failures.remove(0);
            sentTo.add(address.address());
            messages.add(message);
            return new SendResult("msg-" + sentTo.size());
        }
    }

    /** Danh bạ giả: userId -> (email, tên, active). */
    static final class FakeDirectory implements RecipientDirectoryPort {
        final Map<UUID, Recipient> people = new HashMap<>();
        boolean down;
        String tenantName = "Hiệu sách Mẫu";

        void add(UUID user, String email, String name, boolean active) {
            people.put(user, new Recipient(user, email, name, active));
        }

        @Override
        public Lookup lookup(UUID tenantId, java.util.Collection<UUID> userIds) {
            if (down) throw new RecipientDirectoryException("down", null);
            Map<UUID, Recipient> out = new HashMap<>();
            for (UUID id : userIds) if (people.containsKey(id)) out.put(id, people.get(id));
            return new Lookup(tenantName, out);
        }
    }

    static final class InMemoryDeliveries implements DeliveryPort {
        final List<Delivery> rows = new ArrayList<>();
        final Map<UUID, UUID> tenantOf = new HashMap<>();
        private final Set<String> keys = new HashSet<>();
        private final Clock clock;

        InMemoryDeliveries(Clock clock) {
            this.clock = clock;
        }

        private List<Delivery> mine() {
            UUID t = TenantContext.require();
            return rows.stream().filter(d -> t.equals(tenantOf.get(d.id()))).toList();
        }

        @Override
        public boolean insertIfAbsent(Delivery d) {
            UUID t = TenantContext.require();
            if (!keys.add(t + "|" + d.eventId() + "|" + d.channel() + "|" + d.recipientKey())) return false;
            rows.add(d);
            tenantOf.put(d.id(), t);
            return true;
        }

        @Override
        public List<Delivery> leaseDue(int limit, Instant now) {
            List<Delivery> out = mine().stream()
                    .filter(d -> (d.status() == DeliveryStatus.PENDING || d.status() == DeliveryStatus.RETRY)
                            && !d.nextAttemptAt().isAfter(now))
                    .sorted(Comparator.comparing(Delivery::nextAttemptAt)).limit(limit).toList();
            out.forEach(d -> d.markSending(now));
            return out;
        }

        @Override public void save(Delivery delivery) { /* cùng một đối tượng trong bộ nhớ */ }

        @Override
        public Optional<Delivery> find(UUID id) {
            return mine().stream().filter(d -> d.id().equals(id)).findFirst();
        }

        @Override
        public long countSentSince(Instant since) {
            return mine().stream().filter(d -> d.status() == DeliveryStatus.SENT && !d.sentAt().isBefore(since)).count();
        }

        @Override
        public long countSentSinceAllTenants(Instant since) {
            return rows.stream().filter(d -> d.status() == DeliveryStatus.SENT && !d.sentAt().isBefore(since)).count();
        }

        @Override
        public int releaseStuck(Instant staleBefore, Instant now) {
            List<Delivery> stuck = mine().stream()
                    .filter(d -> d.status() == DeliveryStatus.SENDING && d.updatedAt().isBefore(staleBefore)).toList();
            stuck.forEach(d -> d.releaseStuck(now));
            return stuck.size();
        }

        @Override
        public DeliveryPage search(DeliveryCriteria c) {
            List<Delivery> all = mine().stream().filter(d -> c.status() == null || d.status() == c.status())
                    .sorted(Comparator.comparing(Delivery::createdAt).reversed()).toList();
            return new DeliveryPage(all, all.size(), c.page(), c.size());
        }

        @Override
        public List<Delivery> findByRecipient(UUID userId, int limit) {
            return mine().stream().filter(d -> userId.equals(d.recipientUserId())).limit(limit).toList();
        }

        @Override
        public int deleteFinishedBefore(Instant before) {
            List<Delivery> old = mine().stream()
                    .filter(d -> d.status().terminal() && d.updatedAt().isBefore(before)).toList();
            rows.removeAll(old);
            return old.size();
        }

        List<Delivery> all(UUID tenant) {
            return rows.stream().filter(d -> tenant.equals(tenantOf.get(d.id()))).toList();
        }
    }

    /** Cấu hình thông báo trong bộ nhớ: mẫu tenant, kênh, tùy chọn, danh sách tenant. */
    static final class InMemoryConfig implements TemplateStorePort, ChannelSettingsPort, PreferencesPort,
            TenantRegistryPort {
        private final Map<String, MessageTemplate> templates = new HashMap<>();
        private final Map<String, Integer> versions = new HashMap<>();
        private final Map<String, Boolean> channels = new HashMap<>();
        private final Map<String, Boolean> prefs = new HashMap<>();
        private final Set<UUID> tenants = new java.util.LinkedHashSet<>();

        private static String t() { return TenantContext.require().toString(); }

        @Override
        public Optional<MessageTemplate> findActive(NotificationType type, NotificationChannel channel) {
            return Optional.ofNullable(templates.get(t() + "|" + type + "|" + channel));
        }

        @Override
        public List<StoredTemplate> listActive() {
            return templates.entrySet().stream().filter(e -> e.getKey().startsWith(t() + "|")).map(e -> {
                String[] p = e.getKey().split("\\|");
                return new StoredTemplate(NotificationType.valueOf(p[1]), NotificationChannel.valueOf(p[2]),
                        e.getValue(), Instant.EPOCH, null);
            }).toList();
        }

        @Override
        public MessageTemplate saveNewVersion(NotificationType type, NotificationChannel channel, String subject,
                                              String body, UUID by, Instant now) {
            String key = t() + "|" + type + "|" + channel;
            int v = versions.merge(key, 1, Integer::sum);
            MessageTemplate m = new MessageTemplate(subject, body, MessageTemplate.TENANT, v);
            templates.put(key, m);
            return m;
        }

        @Override
        public boolean isEnabled(NotificationType type, NotificationChannel channel) {
            return channels.getOrDefault(t() + "|" + type + "|" + channel, true);
        }

        @Override
        public List<ChannelSetting> listExplicit() {
            return channels.entrySet().stream().filter(e -> e.getKey().startsWith(t() + "|")).map(e -> {
                String[] p = e.getKey().split("\\|");
                return new ChannelSetting(NotificationType.valueOf(p[1]), NotificationChannel.valueOf(p[2]), e.getValue());
            }).toList();
        }

        @Override
        public void upsert(NotificationType type, NotificationChannel channel, boolean enabled, UUID by, Instant now) {
            channels.put(t() + "|" + type + "|" + channel, enabled);
        }

        @Override
        public boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel) {
            return prefs.getOrDefault(t() + "|" + userId + "|" + category + "|" + channel, true);
        }

        @Override
        public List<Preference> listExplicit(UUID userId) {
            String prefix = t() + "|" + userId + "|";
            return prefs.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(e -> {
                String[] p = e.getKey().split("\\|");
                return new Preference(NotificationCategory.valueOf(p[2]), NotificationChannel.valueOf(p[3]), e.getValue());
            }).toList();
        }

        @Override
        public void upsert(UUID userId, NotificationCategory category, NotificationChannel channel, boolean enabled,
                           Instant now) {
            prefs.put(t() + "|" + userId + "|" + category + "|" + channel, enabled);
        }

        @Override public void registerIfAbsent(UUID tenantId, Instant now) { tenants.add(tenantId); }
        @Override public List<UUID> allTenantIds() { return List.copyOf(tenants); }
    }

    final TestClock clock = new TestClock();
    final InMemoryNotifications inApp = new InMemoryNotifications();
    final InMemoryDeliveries deliveries = new InMemoryDeliveries(clock);
    final InMemoryConfig config = new InMemoryConfig();
    final FakeProvider provider = new FakeProvider();
    final FakeDirectory directory = new FakeDirectory();
    final ClasspathDefaultTemplates defaults = new ClasspathDefaultTemplates();
    final TemplateResolver resolver = new TemplateResolver(config, defaults);
    final NotificationProviderRegistry registry;
    final EnqueueNotificationUseCase enqueue;
    final DeliveryTransactions tx = new DeliveryTransactions(deliveries);
    final DispatchNotificationsUseCase dispatch;
    final DeliveryUseCases deliveryUseCases = new DeliveryUseCases(deliveries, clock);
    final NotificationSettingsUseCases settings = new NotificationSettingsUseCases(config, config, clock);
    final NotificationTemplateUseCases templates = new NotificationTemplateUseCases(config, defaults, resolver, clock);

    NotificationKit() {
        this(new DispatchNotificationsUseCase.Settings(50, 60, 600, Duration.ofMinutes(5), RetryPolicy.DEFAULT), true);
    }

    NotificationKit(DispatchNotificationsUseCase.Settings settings, boolean withProvider) {
        this.registry = new NotificationProviderRegistry(withProvider ? List.of(provider) : List.of());
        this.enqueue = new EnqueueNotificationUseCase(inApp, deliveries, config, config, config, registry, resolver,
                clock);
        this.dispatch = new DispatchNotificationsUseCase(tx, config, registry, directory, resolver, settings, clock);
    }
}
