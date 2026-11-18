package lms.api.support.client.http;

import java.util.List;
import java.util.UUID;
import lms.api.support.client.NotificationAdminClient;
import lms.api.support.dto.NotificationAdminDtos.ChannelSettingView;
import lms.api.support.dto.NotificationAdminDtos.DeliveryPageView;
import lms.api.support.dto.NotificationAdminDtos.DeliveryView;
import lms.api.support.dto.NotificationAdminDtos.MyDeliveryView;
import lms.api.support.dto.NotificationAdminDtos.PreferenceView;
import lms.api.support.dto.NotificationAdminDtos.PreviewTemplateRequest;
import lms.api.support.dto.NotificationAdminDtos.SaveTemplateRequest;
import lms.api.support.dto.NotificationAdminDtos.TemplatePreviewView;
import lms.api.support.dto.NotificationAdminDtos.TemplateView;
import lms.api.support.dto.NotificationAdminDtos.UpdateChannelsRequest;
import lms.api.support.dto.NotificationAdminDtos.UpdatePreferencesRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi các API nội bộ thông báo của support-service; DTO trùng hình dạng nên đọc thẳng vào DTO của FE. */
public class HttpNotificationAdminClient implements NotificationAdminClient {

    private final RestClient client;

    public HttpNotificationAdminClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<PreferenceView> myPreferences() {
        return orEmpty(client.get().uri("/internal/notifications/me/preferences").retrieve()
                .body(new ParameterizedTypeReference<List<PreferenceView>>() { }));
    }

    @Override
    public List<PreferenceView> updateMyPreferences(UpdatePreferencesRequest request) {
        return orEmpty(client.put().uri("/internal/notifications/me/preferences").body(request).retrieve()
                .body(new ParameterizedTypeReference<List<PreferenceView>>() { }));
    }

    @Override
    public List<MyDeliveryView> myDeliveries() {
        return orEmpty(client.get().uri("/internal/notifications/me/deliveries").retrieve()
                .body(new ParameterizedTypeReference<List<MyDeliveryView>>() { }));
    }

    @Override
    public DeliveryPageView searchDeliveries(String status, String channel, String type, String from, String to,
                                             int page, int size) {
        DeliveryPageView result = client.get().uri(b -> {
            b.path("/internal/notification-deliveries").queryParam("page", page).queryParam("size", size);
            if (status != null && !status.isBlank()) b.queryParam("status", status);
            if (channel != null && !channel.isBlank()) b.queryParam("channel", channel);
            if (type != null && !type.isBlank()) b.queryParam("type", type);
            if (from != null && !from.isBlank()) b.queryParam("from", from);
            if (to != null && !to.isBlank()) b.queryParam("to", to);
            return b.build();
        }).retrieve().body(DeliveryPageView.class);
        return result == null ? new DeliveryPageView(List.of(), 0, page, size) : result;
    }

    @Override
    public DeliveryView retryDelivery(UUID id) {
        return client.post().uri("/internal/notification-deliveries/{id}/retry", id).retrieve().body(DeliveryView.class);
    }

    @Override
    public List<ChannelSettingView> channelSettings() {
        return orEmpty(client.get().uri("/internal/notification-settings/channels").retrieve()
                .body(new ParameterizedTypeReference<List<ChannelSettingView>>() { }));
    }

    @Override
    public List<ChannelSettingView> updateChannelSettings(UpdateChannelsRequest request) {
        return orEmpty(client.put().uri("/internal/notification-settings/channels").body(request).retrieve()
                .body(new ParameterizedTypeReference<List<ChannelSettingView>>() { }));
    }

    @Override
    public List<TemplateView> templates() {
        return orEmpty(client.get().uri("/internal/notification-templates").retrieve()
                .body(new ParameterizedTypeReference<List<TemplateView>>() { }));
    }

    @Override
    public TemplateView template(String type, String channel) {
        return client.get().uri("/internal/notification-templates/{type}/{channel}", type, channel).retrieve()
                .body(TemplateView.class);
    }

    @Override
    public TemplateView saveTemplate(String type, String channel, SaveTemplateRequest request) {
        return client.put().uri("/internal/notification-templates/{type}/{channel}", type, channel).body(request)
                .retrieve().body(TemplateView.class);
    }

    @Override
    public TemplatePreviewView previewTemplate(String type, String channel, PreviewTemplateRequest request) {
        return client.post().uri("/internal/notification-templates/{type}/{channel}/preview", type, channel)
                .body(request).retrieve().body(TemplatePreviewView.class);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
