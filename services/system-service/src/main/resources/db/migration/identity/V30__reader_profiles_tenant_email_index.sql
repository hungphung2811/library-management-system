-- Rà soát index (docs/architecture/ra-soat-n-plus-1-va-index-sales-identity-support.md). Chỉ DDL, bảng giữ RLS+FORCE.

-- SpringDataReaderProfileRepository.findUserIdsByEmail: where tenant_id = :t (@TenantId) and lower(email) = lower(:email).
-- Đăng nhập ngoài (ExternalSignInUseCase) chạy truy vấn này cho TỪNG tenant ACTIVE; reader_profiles không có index nào trên
-- email nên mỗi lần là một lần quét tuần tự hồ sơ độc giả của tenant.
CREATE INDEX idx_reader_profiles_tenant_lower_email ON reader_profiles (tenant_id, lower(email));
