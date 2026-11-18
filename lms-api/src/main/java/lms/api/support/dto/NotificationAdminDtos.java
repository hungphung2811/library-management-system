package lms.api.support.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO cho FE của thông báo theo sự kiện (ADR-0030): tùy chọn nhận, lịch sử gửi, cấu hình kênh, mẫu nội dung. Giá trị
 * liệt kê (nhóm, kênh, loại) là chuỗi: support-service kiểm và trả 400 khi sai. Địa chỉ người nhận luôn ở dạng đã che.
 */
public final class NotificationAdminDtos {

    private NotificationAdminDtos() {
    }

    // ---- tùy chọn nhận của tôi ----

    public record PreferenceView(String category, String channel, boolean enabled, boolean mandatory) {
    }

    public record PreferenceItem(@NotBlank String category, @NotBlank String channel, @NotNull Boolean enabled) {
    }

    public record UpdatePreferencesRequest(@NotNull @Valid List<@NotNull @Valid PreferenceItem> items) {
    }

    public record MyDeliveryView(UUID id, String type, String category, String channel, String status,
                                 String skipReason, String recipientAddressMasked, Instant sentAt, Instant createdAt) {
    }

    // ---- lịch sử gửi (quản trị) ----

    public record DeliveryView(UUID id, UUID eventId, String eventType, String type, String category, String channel,
                               String status, String skipReason, UUID recipientUserId, String recipientAddressMasked,
                               int attempts, Instant nextAttemptAt, String lastErrorCode, Instant lastErrorAt,
                               Instant sentAt, String templateSource, Integer templateVersion, UUID lastRetryBy,
                               Instant lastRetryAt, Instant createdAt) {
    }

    public record DeliveryPageView(List<DeliveryView> items, long total, int page, int size) {
    }

    // ---- cấu hình kênh theo tenant ----

    public record ChannelSettingView(String type, String category, String channel, boolean enabled) {
    }

    public record ChannelItem(@NotBlank String type, @NotBlank String channel, @NotNull Boolean enabled) {
    }

    public record UpdateChannelsRequest(@NotNull @Valid List<@NotNull @Valid ChannelItem> items) {
    }

    // ---- mẫu nội dung ----

    public record TemplateView(String type, String category, String channel, String source, int version,
                               String subject, String body, List<String> allowedParams) {
    }

    public record SaveTemplateRequest(@NotBlank @Size(max = 200) String subject,
                                      @NotBlank @Size(max = 5000) String body) {
    }

    public record PreviewTemplateRequest(@Size(max = 200) String subject, @Size(max = 5000) String body) {
    }

    public record TemplatePreviewView(String subject, String body) {
    }
}
