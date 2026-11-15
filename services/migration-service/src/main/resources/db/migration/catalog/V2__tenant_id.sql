-- Cách ly dữ liệu catalog theo tenant: đầu sách và bản sao thuộc một tenant.
-- Dữ liệu hiện có về tenant mặc định (cùng id với tenant mặc định do migration identity V11 tạo; catalog_db là DB
-- riêng nên không có khóa ngoại tới bảng tenants).

-- ===== Cột tenant_id + backfill tenant mặc định =====
ALTER TABLE titles ADD COLUMN tenant_id UUID;
UPDATE titles SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE titles ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE book_copies ADD COLUMN tenant_id UUID;
UPDATE book_copies SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE book_copies ALTER COLUMN tenant_id SET NOT NULL;

-- ===== Ràng buộc unique theo tenant =====
DROP INDEX uq_titles_isbn;
CREATE UNIQUE INDEX uq_titles_isbn ON titles (tenant_id, isbn) WHERE isbn IS NOT NULL;

ALTER TABLE book_copies DROP CONSTRAINT book_copies_barcode_key;
ALTER TABLE book_copies ADD CONSTRAINT uq_book_copies_tenant_barcode UNIQUE (tenant_id, barcode);

-- Bản sao phải thuộc đầu sách cùng tenant (khóa ngoại kép)
ALTER TABLE titles ADD CONSTRAINT uq_titles_id_tenant UNIQUE (id, tenant_id);
ALTER TABLE book_copies DROP CONSTRAINT book_copies_title_id_fkey;
ALTER TABLE book_copies ADD CONSTRAINT fk_book_copies_title_tenant
    FOREIGN KEY (title_id, tenant_id) REFERENCES titles (id, tenant_id);

CREATE INDEX idx_titles_tenant_name ON titles (tenant_id, lower(name));

-- ===== Row-Level Security (fail-closed: không có app.tenant_id thì không thấy và không ghi được dòng nào) =====
-- Mẫu chung của tenant-common (TenantRlsConnectionInitializer). FORCE để cả chủ bảng cũng chịu RLS: migration sau
-- ghi vào hai bảng này phải đặt app.tenant_id hoặc chạy bằng superuser.
ALTER TABLE titles ENABLE ROW LEVEL SECURITY;
ALTER TABLE titles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON titles
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE book_copies ENABLE ROW LEVEL SECURITY;
ALTER TABLE book_copies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON book_copies
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
