-- Man hinh log van hanh sales (ADR-0029, luu log vao sales_db): dang ky 2 khoa endpoint cua OperationalLogController.
-- Khoa = "TenController/tenMethod" (ADR-0003). Chi dang ky, KHONG gan mau vai tro nao: chi is_admin co (bo qua kiem khoa);
-- tenant admin gan tay cho vai tro can xem. Ten file co y khong bat dau bang "sales_" (SalesPermissionSeedMatchesCodeTest
-- chi doi chieu file V*__sales_*.sql). permissions la bang TOAN CUC (khong RLS); ON CONFLICT DO NOTHING giu ban tu dang ky.
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('OperationalLogController/integration', 'sales-service', 'GET', '/internal/sales/logs/integration'),
    ('OperationalLogController/system',      'sales-service', 'GET', '/internal/sales/logs/system')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;
