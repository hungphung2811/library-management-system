-- Rà soát index (docs/architecture/ra-soat-n-plus-1-va-index-catalog-circulation.md). Chỉ DDL, không ghi dữ liệu.
-- Bảng borrowings giữ RLS+FORCE từ V6. Không cần CONCURRENTLY cho bảng cỡ vừa; bảng rất lớn thì tạo tay trước.

-- Dùng bởi SpringDataBorrowingRepository.findByUserIdOrdered và BorrowingPersistenceAdapter.search khi lọc theo độc giả:
-- WHERE tenant_id = ? AND user_id = ? ORDER BY borrow_date DESC, id (lịch sử mượn của một độc giả, có phân trang).
-- idx_borrowings_tenant_user (V6) chỉ có (tenant_id, user_id) nên phải sắp xếp lại; chỉ mục mới cho phép đọc theo thứ tự
-- và dừng sớm với LIMIT. idx_borrowings_tenant_user thành dư (tiền tố của chỉ mục này), xem đề xuất bỏ trong báo cáo.
CREATE INDEX IF NOT EXISTS idx_borrowings_tenant_user_borrow_date
    ON borrowings (tenant_id, user_id, borrow_date DESC, id);
