package lms.support.notification.application;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import lms.support.notification.domain.NotificationChannel;

/** Tra provider theo kênh; kênh không có provider thì rỗng (delivery SKIPPED NO_PROVIDER). */
public class NotificationProviderRegistry {

    private final Map<NotificationChannel, NotificationProvider> providers = new EnumMap<>(NotificationChannel.class);

    public NotificationProviderRegistry(Iterable<? extends NotificationProvider> beans) {
        for (NotificationProvider p : beans) {
            providers.put(p.channel(), p);
        }
    }

    public Optional<NotificationProvider> find(NotificationChannel channel) {
        return Optional.ofNullable(providers.get(channel));
    }

    public boolean has(NotificationChannel channel) {
        return providers.containsKey(channel);
    }
}
