-- ADR-0018 mục 2.2: chỉ mục đếm bản sao theo trạng thái cho dashboard. Chỉ DDL (bảng giữ RLS+FORCE từ V2).
CREATE INDEX idx_book_copies_tenant_status ON book_copies (tenant_id, status);
