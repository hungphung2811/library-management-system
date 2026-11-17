-- ADR-0018 mục 2.5: khóa quyền thống kê/báo cáo (dashboard) và gán mặc định cho role mẫu. Cấu trúc theo V22.
-- permissions, role_templates, role_template_permissions là bảng TOÀN CỤC (không RLS); chỉ phần backfill ghi
-- roles/role_permissions (RLS FORCE) nên đặt app.tenant_id từng tenant.
-- Mẫu thật (V3/V11/V16/V17): LIBRARY_ADMIN (a2), LIBRARIAN (a3) thuộc LENDING; STORE_MANAGER (c3) thuộc SALES.
-- Khóa XUẤT báo cáo (LendingReportController/overdueBorrowingsCsv, SalesReportController/stockCsv, ordersCsv) chỉ ĐĂNG KÝ,
-- KHÔNG gán cho mẫu nào: tenant admin (is_admin, bỏ qua kiểm khóa) và role được gán tay.

-- ===== 1. Khóa endpoint =====
-- Đường dẫn là đường nội bộ của service (/internal/**), khóa thật do service tự đăng ký lại (ON CONFLICT DO NOTHING).
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('CatalogStatsController/summary',                 'catalog-service',     'GET',  '/internal/reporting/catalog/summary'),
    ('BookCopyController/lookup',                      'catalog-service',     'POST', '/internal/book-copies/lookup'),
    ('LendingStatsController/summary',                 'circulation-service', 'GET',  '/internal/reporting/lending/summary'),
    ('LendingStatsController/borrowingTrend',          'circulation-service', 'GET',  '/internal/reporting/lending/borrowing-trend'),
    ('LendingStatsController/topBorrowedTitles',       'circulation-service', 'GET',  '/internal/reporting/lending/top-titles'),
    ('LendingReportController/overdueBorrowingsCsv',   'circulation-service', 'GET',  '/internal/reporting/lending/overdue-borrowings.csv'),
    ('SalesStatsController/summary',                   'sales-service',       'GET',  '/internal/sales/reporting/summary'),
    ('SalesStatsController/revenueTrend',              'sales-service',       'GET',  '/internal/sales/reporting/revenue-trend'),
    ('SalesStatsController/topSoldTitles',             'sales-service',       'GET',  '/internal/sales/reporting/top-titles'),
    ('SalesReportController/stockCsv',                 'sales-service',       'GET',  '/internal/sales/reporting/stock.csv'),
    ('SalesReportController/ordersCsv',                'sales-service',       'GET',  '/internal/sales/reporting/orders.csv'),
    ('UserController/lookup',                          'identity-service',    'POST', '/internal/users/lookup'),
    ('TitleController/lookup',                         'catalog-service',     'POST', '/internal/books/lookup')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Backfill tenant ĐÃ bật module =====
-- Chạy TRƯỚC khi cập nhật mẫu (mục 3): so khóa hiện có của role với mẫu hiện tại để biết role còn nguyên.
-- Chỉ role CÒN NGUYÊN MẪU mới được thêm khóa; role đã chỉnh hoặc không còn thì để nguyên và ghi WARNING để tenant admin gán tay.
-- Role quản trị (is_admin) bỏ qua kiểm khóa nên không cần.
DO $$
DECLARE
    t        RECORD;
    tpl      RECORD;
    r        RECORD;
    new_keys TEXT[];
    pristine BOOLEAN;
BEGIN
    FOR t IN SELECT tm.tenant_id, tm.module FROM tenant_modules tm
             WHERE tm.module IN ('LENDING', 'SALES') AND tm.enabled ORDER BY tm.tenant_id, tm.module LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);

        FOR tpl IN SELECT id, name FROM role_templates
                   WHERE module = t.module
                     AND id IN ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-0000000000a3',
                                '00000000-0000-0000-0000-0000000000c3')
                   ORDER BY name LOOP
            IF tpl.id = '00000000-0000-0000-0000-0000000000c3' THEN
                new_keys := ARRAY['CatalogStatsController/summary', 'SalesStatsController/summary',
                                  'SalesStatsController/revenueTrend', 'SalesStatsController/topSoldTitles'];
            ELSE
                new_keys := ARRAY['CatalogStatsController/summary', 'LendingStatsController/summary',
                                  'LendingStatsController/borrowingTrend', 'LendingStatsController/topBorrowedTitles',
                                  'BookCopyController/lookup', 'UserController/lookup', 'TitleController/lookup'];
            END IF;
            SELECT ro.id, ro.is_admin INTO r FROM roles ro WHERE ro.tenant_id = t.tenant_id AND ro.name = tpl.name;
            IF NOT FOUND THEN
                RAISE WARNING 'V25: tenant % không có role % (đã xóa hoặc đổi tên): không gán khóa thống kê, gán tay nếu cần',
                    t.tenant_id, tpl.name;
                CONTINUE;
            END IF;
            IF r.is_admin THEN
                CONTINUE;
            END IF;
            pristine :=
                NOT EXISTS (SELECT 1 FROM role_permissions rp
                            WHERE rp.role_id = r.id AND rp.permission_code NOT IN (
                                SELECT x.permission_code FROM role_template_permissions x WHERE x.template_id = tpl.id))
                AND NOT EXISTS (SELECT 1 FROM role_template_permissions x
                                WHERE x.template_id = tpl.id AND x.permission_code NOT IN (
                                    SELECT rp.permission_code FROM role_permissions rp WHERE rp.role_id = r.id));
            IF pristine THEN
                INSERT INTO role_permissions (role_id, permission_code)
                SELECT r.id, k FROM unnest(new_keys) AS k
                ON CONFLICT DO NOTHING;
            ELSE
                RAISE WARNING 'V25: tenant % role % đã bị chỉnh so với mẫu: KHÔNG tự gán khóa thống kê, gán tay các khóa %',
                    t.tenant_id, tpl.name, new_keys;
            END IF;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- ===== 3. Cập nhật mẫu (tenant mới nhận mẫu mới) =====
INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'CatalogStatsController/summary'),
    ('00000000-0000-0000-0000-0000000000a2', 'LendingStatsController/summary'),
    ('00000000-0000-0000-0000-0000000000a2', 'LendingStatsController/borrowingTrend'),
    ('00000000-0000-0000-0000-0000000000a2', 'LendingStatsController/topBorrowedTitles'),
    ('00000000-0000-0000-0000-0000000000a2', 'BookCopyController/lookup'),
    ('00000000-0000-0000-0000-0000000000a2', 'UserController/lookup'),
    ('00000000-0000-0000-0000-0000000000a2', 'TitleController/lookup'),
    ('00000000-0000-0000-0000-0000000000a3', 'CatalogStatsController/summary'),
    ('00000000-0000-0000-0000-0000000000a3', 'LendingStatsController/summary'),
    ('00000000-0000-0000-0000-0000000000a3', 'LendingStatsController/borrowingTrend'),
    ('00000000-0000-0000-0000-0000000000a3', 'LendingStatsController/topBorrowedTitles'),
    ('00000000-0000-0000-0000-0000000000a3', 'BookCopyController/lookup'),
    ('00000000-0000-0000-0000-0000000000a3', 'UserController/lookup'),
    ('00000000-0000-0000-0000-0000000000a3', 'TitleController/lookup'),
    ('00000000-0000-0000-0000-0000000000c3', 'CatalogStatsController/summary'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesStatsController/summary'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesStatsController/revenueTrend'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesStatsController/topSoldTitles')
ON CONFLICT DO NOTHING;
