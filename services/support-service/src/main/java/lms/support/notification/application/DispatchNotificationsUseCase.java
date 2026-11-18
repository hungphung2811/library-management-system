package lms.support.notification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.support.notification.application.NotificationConfigPorts.TenantRegistryPort;
import lms.support.notification.application.NotificationProvider.PermanentSendException;
import lms.support.notification.application.NotificationProvider.RenderedMessage;
import lms.support.notification.application.NotificationProvider.ResolvedAddress;
import lms.support.notification.application.NotificationProvider.TransientSendException;
import lms.support.notification.application.RecipientDirectoryPort.Lookup;
import lms.support.notification.application.RecipientDirectoryPort.Recipient;
import lms.support.notification.domain.AddressMasker;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.RetryPolicy;
import lms.support.notification.domain.SkipReason;
import lms.support.notification.domain.TemplateRenderer;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xả hàng chờ (ADR-0030 D4, D8, D9). Mỗi tenant: lấy lô đến hạn (khóa, chuyển SENDING, commit), tra địa chỉ theo lô ở
 * identity, dựng nội dung từ mẫu, gửi qua provider NGOÀI transaction rồi ghi kết quả. Giới hạn tốc độ đếm bằng DB nên
 * đúng khi nhiều instance: dừng lấy thêm khi số thư SENT 60 giây qua của tenant đạt trần tenant hoặc của cả hệ thống đạt
 * trần toàn cục; phần còn lại giữ nguyên trạng thái chờ cho lượt sau.
 */
public class DispatchNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchNotificationsUseCase.class);
    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** Tham số vận hành (lms.notification.*). */
    public record Settings(int batchSize, int perTenantPerMinute, int globalPerMinute, Duration stuckAfter,
                           RetryPolicy retry) {
        public static Settings defaults() {
            return new Settings(50, 60, 600, Duration.ofMinutes(5), RetryPolicy.DEFAULT);
        }
    }

    private final DeliveryTransactions tx;
    private final TenantRegistryPort tenants;
    private final NotificationProviderRegistry providers;
    private final RecipientDirectoryPort directory;
    private final TemplateResolver templates;
    private final Settings settings;
    private final Clock clock;

    public DispatchNotificationsUseCase(DeliveryTransactions tx, TenantRegistryPort tenants,
                                        NotificationProviderRegistry providers, RecipientDirectoryPort directory,
                                        TemplateResolver templates, Settings settings, Clock clock) {
        this.tx = tx;
        this.tenants = tenants;
        this.providers = providers;
        this.directory = directory;
        this.templates = templates;
        this.settings = settings;
        this.clock = clock;
    }

    /** Một lượt của job: lặp mọi tenant, lỗi một tenant không chặn tenant khác. @return số thư đã gửi */
    public int dispatchAll() {
        List<UUID> ids = new ArrayList<>(tenants.allTenantIds());
        if (ids.isEmpty()) return 0;
        Collections.shuffle(ids);
        long globalRemaining = settings.globalPerMinute() - sentAcrossTenants();
        int sent = 0;
        for (UUID tenantId : ids) {
            if (globalRemaining <= 0) break;
            try {
                int n = dispatchTenant(tenantId, globalRemaining);
                sent += n;
                globalRemaining -= n;
            } catch (RuntimeException e) {
                log.error("Dispatch thông báo lỗi ở tenant {}: {}", tenantId, e.toString());
            }
        }
        return sent;
    }

    /** Dọn lượt gửi đã kết thúc quá {@code retention} ở mọi tenant (job hằng ngày). @return số dòng đã xóa */
    public int purgeAll(Duration retention) {
        int deleted = 0;
        for (UUID tenantId : tenants.allTenantIds()) {
            try {
                deleted += TenantContext.callAs(tenantId,
                        () -> tx.purgeFinishedBefore(clock.instant().minus(retention)));
            } catch (RuntimeException e) {
                log.error("Dọn lượt gửi lỗi ở tenant {}: {}", tenantId, e.toString());
            }
        }
        return deleted;
    }

    /** Xả hàng chờ của một tenant, tối đa {@code globalBudget} thư. */
    public int dispatchTenant(UUID tenantId, long globalBudget) {
        return TenantContext.callAs(tenantId, () -> dispatchCurrentTenant(globalBudget));
    }

    private long sentAcrossTenants() {
        try {
            TenantContext.bindAllTenants();
            return tx.countSentSinceAllTenants(clock.instant().minus(WINDOW));
        } finally {
            TenantContext.clear();
        }
    }

    private int dispatchCurrentTenant(long globalBudget) {
        Instant now = clock.instant();
        tx.releaseStuck(now.minus(settings.stuckAfter()), now);
        long recent = tx.countSentSince(now.minus(WINDOW));
        long budget = Math.min(Math.min(settings.perTenantPerMinute() - recent, globalBudget), settings.batchSize());
        if (budget <= 0) return 0;
        List<Delivery> batch = tx.lease((int) budget, now);
        if (batch.isEmpty()) return 0;

        Set<UUID> userIds = new LinkedHashSet<>();
        for (Delivery d : batch) {
            if (d.recipientUserId() != null) userIds.add(d.recipientUserId());
        }
        Lookup lookup = null;
        try {
            lookup = directory.lookup(TenantContext.require(), userIds);
        } catch (RuntimeException e) {
            log.warn("Tra người nhận ở identity lỗi: {}", e.getClass().getSimpleName());
        }
        int sent = 0;
        for (Delivery d : batch) {
            if (process(d, lookup)) sent++;
        }
        return sent;
    }

    /** @return true nếu đã gửi thành công */
    private boolean process(Delivery d, Lookup lookup) {
        Instant now = clock.instant();
        try {
            Optional<NotificationProvider> provider = providers.find(d.channel());
            if (provider.isEmpty()) {
                d.markSkipped(SkipReason.NO_PROVIDER, now);
                return false;
            }
            String tenantName = lookup == null ? "" : nullToEmpty(lookup.tenantDisplayName());
            String address;
            String recipientName = "";
            if (d.recipientUserId() == null) {
                address = d.recipientAddress();
            } else {
                if (lookup == null) {
                    d.markFailed("RECIPIENT_LOOKUP_FAILED", false, settings.retry(), now);
                    return false;
                }
                Recipient r = lookup.recipients().get(d.recipientUserId());
                if (r == null || !r.active()) {
                    d.markSkipped(SkipReason.INACTIVE_USER, now);
                    return false;
                }
                if (r.email() == null || r.email().isBlank()) {
                    d.markSkipped(SkipReason.NO_ADDRESS, now);
                    return false;
                }
                address = r.email();
                recipientName = nullToEmpty(r.displayName());
            }
            if (address == null || address.isBlank()) {
                d.markSkipped(SkipReason.NO_ADDRESS, now);
                return false;
            }
            d.noteAddress(address);
            Optional<MessageTemplate> template = templates.resolve(d.type(), d.channel());
            if (template.isEmpty()) {
                d.markFailed("TEMPLATE_ERROR", true, settings.retry(), now);
                return false;
            }
            Map<String, String> values = new HashMap<>(d.params());
            values.put("recipient_name", recipientName);
            values.put("tenant_name", tenantName);
            MessageTemplate rendered = TemplateRenderer.render(template.get(), values);
            NotificationProvider.SendResult result = provider.get().send(
                    new RenderedMessage(rendered.subject(), rendered.body()),
                    new ResolvedAddress(address, recipientName, tenantName));
            d.markSent(result == null ? null : result.providerMessageId(), template.get(), clock.instant());
            log.info("Đã gửi thông báo {} tới {} (delivery={})", d.type(), AddressMasker.maskEmail(address), d.id());
            return true;
        } catch (TransientSendException e) {
            d.markFailed(e.code(), false, settings.retry(), clock.instant());
            return false;
        } catch (PermanentSendException e) {
            d.markFailed(e.code(), true, settings.retry(), clock.instant());
            return false;
        } catch (RuntimeException e) {
            log.warn("Lỗi không lường trước khi gửi delivery {}: {}", d.id(), e.getClass().getSimpleName());
            d.markFailed("UNEXPECTED_ERROR", false, settings.retry(), clock.instant());
            return false;
        } finally {
            if (d.status() == DeliveryStatus.SENDING) {
                d.releaseStuck(clock.instant());
            }
            tx.record(d);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
