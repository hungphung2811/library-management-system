package lms.api.support.client;

import java.util.List;
import java.util.UUID;
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

/**
 * Cổng sang API thông báo theo sự kiện của support-service (ADR-0030): tùy chọn nhận, lịch sử gửi, cấu hình kênh, mẫu.
 * Quyền do support-service kiểm; lỗi 4xx của service được ném dưới dạng RestClientResponseException.
 */
public interface NotificationAdminClient {

    List<PreferenceView> myPreferences();

    List<PreferenceView> updateMyPreferences(UpdatePreferencesRequest request);

    List<MyDeliveryView> myDeliveries();

    /** status, channel, type, from, to là chuỗi lọc tùy chọn (null/rỗng = không lọc). */
    DeliveryPageView searchDeliveries(String status, String channel, String type, String from, String to, int page,
                                      int size);

    DeliveryView retryDelivery(UUID id);

    List<ChannelSettingView> channelSettings();

    List<ChannelSettingView> updateChannelSettings(UpdateChannelsRequest request);

    List<TemplateView> templates();

    TemplateView template(String type, String channel);

    TemplateView saveTemplate(String type, String channel, SaveTemplateRequest request);

    TemplatePreviewView previewTemplate(String type, String channel, PreviewTemplateRequest request);
}
