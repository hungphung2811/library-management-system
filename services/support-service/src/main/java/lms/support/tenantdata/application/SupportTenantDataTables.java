package lms.support.tenantdata.application;

import java.util.List;
import lms.tenant.export.ExportExclusion;
import lms.tenant.export.ExportTable;

/**
 * Danh sách bảng/cột support xuất cho tenant (ADR-0031 F1), cố định trong mã, khớp migration
 * {@code system-service/.../db/migration/audit} đến V6. Chỉ AUDIT_DB (PostgreSQL); bản thô Mongo (LOG_DB) không xuất vì
 * bản chuẩn đã nằm trong {@code audit_logs}. {@code tenant_id} không xuất (CSV chỉ của một tenant).
 *
 * <p>{@code audit_logs} có thể rất lớn nên chia thành các file {@code audit_logs_001.csv}, {@code _002}, ... mỗi
 * {@link #AUDIT_ROWS_PER_FILE} dòng (bảng rỗng vẫn có {@code audit_logs_001.csv} chỉ dòng tiêu đề).
 * {@code notification_deliveries} không xuất địa chỉ nhận đầy đủ ({@code recipient_address}) và khóa khử trùng
 * ({@code recipient_key}, băm địa chỉ): chỉ {@code recipient_address_masked} (đã che). {@code params} chỉ chứa tham số đã
 * qua danh sách cho phép của từng loại thông báo (mã đơn, số tiền, ngày), không có token/liên kết.
 */
public final class SupportTenantDataTables {

    private SupportTenantDataTables() {
    }

    public static final String SERVICE = "support";
    public static final long AUDIT_ROWS_PER_FILE = 500_000;

    public static final List<ExportTable> TABLES = List.of(
            ExportTable.of("notifications", List.of("id"),
                    "id", "event_id", "user_id", "type", "message", "created_at", "read_at"),
            ExportTable.of("notification_deliveries", List.of("id"),
                    "id", "event_id", "event_type", "notification_type", "category", "channel", "recipient_user_id",
                    "recipient_address_masked", "params", "template_source", "template_version", "status",
                    "skip_reason", "attempts", "attempts_base", "next_attempt_at", "last_error_code", "last_error_at",
                    "sent_at", "provider_message_id", "last_retry_by", "last_retry_at", "created_at", "updated_at")
                    .excluding("recipient_address", "địa chỉ nhận đầy đủ (dữ liệu cá nhân): chỉ xuất bản đã che")
                    .excluding("recipient_key", "khóa khử trùng nội bộ, là băm của địa chỉ nhận"),
            ExportTable.of("notification_templates", List.of("id"),
                    "id", "notification_type", "channel", "version", "subject", "body", "active", "created_by",
                    "created_at"),
            ExportTable.of("notification_channel_settings", List.of("notification_type", "channel"),
                    "notification_type", "channel", "enabled", "updated_by", "updated_at"),
            ExportTable.of("notification_preferences", List.of("user_id", "category", "channel"),
                    "user_id", "category", "channel", "enabled", "updated_at"),
            ExportTable.of("audit_logs", List.of("id"),
                    "id", "event_id", "event_type", "aggregate_id", "occurred_at", "recorded_at", "payload")
                    .splitEvery(AUDIT_ROWS_PER_FILE));

    public static final List<ExportExclusion> EXCLUDED = List.of(
            new ExportExclusion("notification_tenants",
                    "danh bạ tenant nội bộ của bộ gửi thông báo (chỉ id tenant, không có tenant_id nghiệp vụ)"),
            new ExportExclusion("mongo.raw_events",
                    "bản thô sự kiện trong MongoDB (LOG_DB); bản chuẩn đã có trong audit_logs"));
}
