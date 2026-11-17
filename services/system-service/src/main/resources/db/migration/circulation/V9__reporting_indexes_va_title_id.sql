-- ADR-0018 mục 2.2: chỉ mục cho thống kê/báo cáo (dashboard) và cột snapshot borrowing_items.title_id (top sách mượn).
-- Chỉ DDL: không ghi dữ liệu nên không cần set_config('app.tenant_id'). Bảng giữ nguyên RLS+FORCE từ V6.
-- title_id NULL cho dòng mượn cũ (circulation không đọc được catalog để backfill, ADR-0018 mục 2.2): top sách mượn bỏ qua
-- các dòng này. BorrowBookUseCase ghi title_id từ CopyInfo.titleId của dòng mượn mới (việc của be-dev circulation: entity
-- của borrowing_items cần thêm trường title_id, UUID nullable).
ALTER TABLE borrowing_items ADD COLUMN title_id UUID;

CREATE INDEX idx_borrowings_tenant_borrow_date ON borrowings (tenant_id, borrow_date);
CREATE INDEX idx_borrowings_tenant_due_date ON borrowings (tenant_id, due_date);
CREATE INDEX idx_borrowing_items_tenant_return_date ON borrowing_items (tenant_id, return_date)
    WHERE return_date IS NOT NULL;
