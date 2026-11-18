package lms.support.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Một lượt gửi thông báo ra ngoài (người nhận x kênh x sự kiện): vừa là hàng chờ của dispatcher vừa là nhật ký gửi.
 * Khóa khử trùng (tenant, eventId, channel, recipientKey) do cổng lưu trữ bảo đảm. Không lưu nội dung đã dựng hay
 * lỗi gốc của SMTP (có thể chứa địa chỉ): chỉ mã lỗi.
 */
public class Delivery {

    private final UUID id;
    private final UUID eventId;
    private final String eventType;
    private final NotificationType type;
    private final NotificationChannel channel;
    private final UUID recipientUserId;
    private final String recipientKey;
    private String recipientAddress;
    private String recipientAddressMasked;
    private final Map<String, String> params;
    private final Instant createdAt;

    private String templateSource;
    private Integer templateVersion;
    private DeliveryStatus status;
    private SkipReason skipReason;
    private int attempts;
    private int attemptsBase;
    private Instant nextAttemptAt;
    private String lastErrorCode;
    private Instant lastErrorAt;
    private Instant sentAt;
    private String providerMessageId;
    private UUID lastRetryBy;
    private Instant lastRetryAt;
    private Instant updatedAt;

    @SuppressWarnings("java:S107")
    public Delivery(UUID id, UUID eventId, String eventType, NotificationType type, NotificationChannel channel,
                    UUID recipientUserId, String recipientKey, String recipientAddress, String recipientAddressMasked,
                    Map<String, String> params, String templateSource, Integer templateVersion, DeliveryStatus status,
                    SkipReason skipReason, int attempts, int attemptsBase, Instant nextAttemptAt, String lastErrorCode,
                    Instant lastErrorAt, Instant sentAt, String providerMessageId, UUID lastRetryBy, Instant lastRetryAt,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.type = type;
        this.channel = channel;
        this.recipientUserId = recipientUserId;
        this.recipientKey = recipientKey;
        this.recipientAddress = recipientAddress;
        this.recipientAddressMasked = recipientAddressMasked;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.templateSource = templateSource;
        this.templateVersion = templateVersion;
        this.status = status;
        this.skipReason = skipReason;
        this.attempts = attempts;
        this.attemptsBase = attemptsBase;
        this.nextAttemptAt = nextAttemptAt;
        this.lastErrorCode = lastErrorCode;
        this.lastErrorAt = lastErrorAt;
        this.sentAt = sentAt;
        this.providerMessageId = providerMessageId;
        this.lastRetryBy = lastRetryBy;
        this.lastRetryAt = lastRetryAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Người nhận có tài khoản: khóa khử trùng là userId; địa chỉ tra ở identity lúc gửi. */
    public static Delivery pendingForUser(UUID eventId, String eventType, NotificationType type,
                                          NotificationChannel channel, UUID userId, Map<String, String> params,
                                          Instant now) {
        return new Delivery(UUID.randomUUID(), eventId, eventType, type, channel, userId, userId.toString(), null,
                null, params, null, null, DeliveryStatus.PENDING, null, 0, 0, now, null, null, null, null, null,
                null, now, now);
    }

    /** Người nhận trực tiếp (khách lẻ, không có tài khoản): lưu địa chỉ tạm tới khi delivery kết thúc. */
    public static Delivery pendingForAddress(UUID eventId, String eventType, NotificationType type,
                                             NotificationChannel channel, String email, Map<String, String> params,
                                             Instant now) {
        return new Delivery(UUID.randomUUID(), eventId, eventType, type, channel, null,
                AddressMasker.emailKey(email), email.trim(), AddressMasker.maskEmail(email), params, null, null,
                DeliveryStatus.PENDING, null, 0, 0, now, null, null, null, null, null, null, now, now);
    }

    /** Bản SKIPPED ngay lúc xếp hàng (không provider, tenant tắt kênh, người dùng từ chối). */
    public Delivery skippedAtEnqueue(SkipReason reason, Instant now) {
        markSkipped(reason, now);
        return this;
    }

    // ---- chuyển trạng thái ----

    public void markSending(Instant now) {
        status = DeliveryStatus.SENDING;
        updatedAt = now;
    }

    public void markSent(String providerMessageId, MessageTemplate used, Instant now) {
        this.status = DeliveryStatus.SENT;
        this.attempts++;
        this.sentAt = now;
        this.providerMessageId = providerMessageId;
        this.templateSource = used.source();
        this.templateVersion = used.version();
        this.lastErrorCode = null;
        this.nextAttemptAt = now;
        finish(now);
    }

    public void markSkipped(SkipReason reason, Instant now) {
        this.status = DeliveryStatus.SKIPPED;
        this.skipReason = reason;
        this.nextAttemptAt = now;
        finish(now);
    }

    /**
     * Ghi một lần thử thất bại. {@code permanent} hoặc hết lượt thì DEAD, ngược lại RETRY theo lịch.
     *
     * @return trạng thái sau khi ghi
     */
    public DeliveryStatus markFailed(String errorCode, boolean permanent, RetryPolicy policy, Instant now) {
        this.attempts++;
        this.lastErrorCode = errorCode;
        this.lastErrorAt = now;
        Optional<Duration> delay = permanent ? Optional.empty() : policy.delayAfter(attempts - attemptsBase);
        if (delay.isPresent()) {
            this.status = DeliveryStatus.RETRY;
            this.nextAttemptAt = now.plus(delay.get());
            this.updatedAt = now;
        } else {
            this.status = DeliveryStatus.DEAD;
            this.nextAttemptAt = now;
            finish(now);
        }
        return status;
    }

    /** Quản trị gửi lại lượt DEAD: về PENDING, giữ lịch sử attempts nhưng mở một đợt thử mới. */
    public void requeue(UUID by, Instant now) {
        if (status != DeliveryStatus.DEAD) throw new DeliveryNotRetryableException();
        this.status = DeliveryStatus.PENDING;
        this.attemptsBase = attempts;
        this.nextAttemptAt = now;
        this.lastRetryBy = by;
        this.lastRetryAt = now;
        this.updatedAt = now;
    }

    /** Dòng kẹt SENDING (instance chết giữa chừng) quay lại RETRY, có thể gửi trùng một lần (D8). */
    public void releaseStuck(Instant now) {
        if (status == DeliveryStatus.SENDING) {
            this.status = DeliveryStatus.RETRY;
            this.nextAttemptAt = now;
            this.updatedAt = now;
        }
    }

    /** Ghi dạng đã che của địa chỉ vừa tra (người nhận có tài khoản): chỉ lưu bản che, không lưu địa chỉ đầy đủ. */
    public void noteAddress(String email) {
        this.recipientAddressMasked = AddressMasker.maskEmail(email);
    }

    private void finish(Instant now) {
        this.recipientAddress = null;
        this.updatedAt = now;
    }

    // ---- truy cập ----

    public UUID id() { return id; }
    public UUID eventId() { return eventId; }
    public String eventType() { return eventType; }
    public NotificationType type() { return type; }
    public NotificationChannel channel() { return channel; }
    public UUID recipientUserId() { return recipientUserId; }
    public String recipientKey() { return recipientKey; }
    public String recipientAddress() { return recipientAddress; }
    public String recipientAddressMasked() { return recipientAddressMasked; }
    public Map<String, String> params() { return params; }
    public String templateSource() { return templateSource; }
    public Integer templateVersion() { return templateVersion; }
    public DeliveryStatus status() { return status; }
    public SkipReason skipReason() { return skipReason; }
    public int attempts() { return attempts; }
    public int attemptsBase() { return attemptsBase; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public String lastErrorCode() { return lastErrorCode; }
    public Instant lastErrorAt() { return lastErrorAt; }
    public Instant sentAt() { return sentAt; }
    public String providerMessageId() { return providerMessageId; }
    public UUID lastRetryBy() { return lastRetryBy; }
    public Instant lastRetryAt() { return lastRetryAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
