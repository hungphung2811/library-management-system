package lms.circulation.tenantdata.application;

import java.util.List;
import lms.tenant.export.ExportExclusion;
import lms.tenant.export.ExportTable;

/**
 * Danh sách bảng/cột circulation xuất cho tenant (ADR-0031 F1), cố định trong mã, khớp migration
 * {@code system-service/.../db/migration/circulation} đến V15. {@code tenant_id} không xuất (CSV chỉ của một tenant).
 * Bảng mới có {@code tenant_id} phải được thêm vào đây hoặc vào {@link #EXCLUDED} kèm lý do (IT so với schema thật).
 * Không có cột bí mật trong circulation; {@code user_id}/{@code reader_id} là id người dùng, tham chiếu sang phần
 * identity của gói (không xuất tên/email ở đây).
 */
public final class CirculationTenantDataTables {

    private CirculationTenantDataTables() {
    }

    public static final String SERVICE = "circulation";

    public static final List<ExportTable> TABLES = List.of(
            ExportTable.of("borrowings", List.of("id"),
                    "id", "user_id", "borrow_date", "due_date", "renew_count", "loan_days"),
            // Không có khóa duy nhất trong schema: phân trang theo (borrowing_id, book_copy_id), đọc nguyên nhóm trùng khóa
            ExportTable.byBusinessKey("borrowing_items", List.of("borrowing_id", "book_copy_id"),
                    "borrowing_id", "book_copy_id", "return_date", "item_condition", "title_id", "condition_grade",
                    "condition_note", "condition_recorded"),
            ExportTable.of("reservations", List.of("id"),
                    "id", "reader_id", "title_id", "reserved_at", "status", "copy_id", "hold_until", "work_id",
                    "language"),
            ExportTable.of("fines", List.of("id"),
                    "id", "reader_id", "borrowing_id", "book_copy_id", "reason", "amount", "status", "created_at",
                    "paid_at", "payable_amount", "base_price", "price_source", "price_fallback", "rate",
                    "damage_level_code", "damage_level_label", "amount_before_clamp", "assessment_note",
                    "evidence_urls"),
            ExportTable.of("fine_adjustment_requests", List.of("id"),
                    "id", "fine_id", "reader_id", "fine_reason", "fine_amount", "kind", "payable_amount", "reason",
                    "status", "requested_by", "requested_at", "decided_by", "decided_at", "decision_note",
                    "version"),
            ExportTable.of("fine_refunds", List.of("id"),
                    "id", "fine_id", "borrowing_id", "book_copy_id", "reader_id", "lost_on", "found_on",
                    "restore_status", "check_note", "refund_method", "reference_code", "mode",
                    "fine_status_at_request", "collected_amount", "processing_fee", "refund_amount", "status",
                    "requested_by", "requested_at", "decided_by", "decided_at", "decision_note", "version"),
            ExportTable.of("circulation_policies", List.of("id"),
                    "id", "version", "loan_days", "max_renewals", "renewal_days", "max_active_loans_per_reader",
                    "fine_per_day", "grace_days", "reservation_hold_days", "updated_at", "updated_by",
                    "due_reminder_days_before", "overdue_reminder_interval_days", "overdue_reminder_max"),
            ExportTable.of("compensation_policies", List.of("id"),
                    "id", "version", "lost_rate", "price_source", "min_amount", "max_amount", "found_window_days",
                    "refund_processing_fee", "updated_at", "updated_by"),
            ExportTable.of("damage_levels", List.of("id"),
                    "id", "code", "label", "rate", "sort_order", "active", "version", "created_at", "updated_at",
                    "updated_by"),
            ExportTable.of("borrow_rules", List.of("reader_type"),
                    "reader_type", "max_items", "loan_days"),
            ExportTable.of("loan_reminders", List.of("id"),
                    "id", "borrowing_id", "kind", "due_date", "seq", "sent_on", "event_id", "created_at"));

    public static final List<ExportExclusion> EXCLUDED = List.of(
            new ExportExclusion("known_tenants",
                    "danh bạ tenant nội bộ của dịch vụ (không thuộc dữ liệu nghiệp vụ của tenant, không có tenant_id)"));
}
