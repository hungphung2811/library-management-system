-- ADR-0030 (goi A hoa don, B chot ca, C thong bao) va ADR-0031 (goi F xuat du lieu tenant): dang ky khoa endpoint va gan
-- mau vai tro. Khoa = "TenController/tenMethod" (ADR-0003); DOI TEN controller/method la doi khoa, phai sua bang
-- migration moi (khong sua file da chay). Bang ten khoa nam o phu luc ADR-0030.
-- * Ten file co y khong bat dau bang "sales_": SalesPermissionSeedMatchesCodeTest chi doi chieu cac file V*__sales_*.sql.
-- * Metadata (service/http_method/path_pattern) chi de man hinh phan quyen hien thi; ON CONFLICT DO NOTHING nen ban tu
--   dang ky luc chay (neu co truoc) duoc giu nguyen.
-- * Gan mau: Thu ngan (c1) va Quan ly cua hang (c3) theo bang ADR-0030 muc 3.2; khach (c4) cho yeu cau hoa don cua minh.
--   Khoa thong bao (goi C) va xuat du lieu (goi F) KHONG gan mau nao: chi is_admin co (bo qua kiem khoa).
-- * Chi bang toan cuc role_template_permissions: KHONG backfill role cua tenant dang chay (tenant admin gan tay), nhu V38.
-- permissions, role_templates, role_template_permissions la bang TOAN CUC (khong RLS).

-- ===== 1. Khoa endpoint =====
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    -- Goi A: hoa don (sales-service, invoicing.infrastructure.web)
    ('InvoiceController/search',          'sales-service', 'GET',  '/internal/sales/invoices'),
    ('InvoiceController/get',             'sales-service', 'GET',  '/internal/sales/invoices/{id}'),
    ('InvoiceController/print',           'sales-service', 'GET',  '/internal/sales/invoices/{id}/print'),
    ('InvoiceController/issue',           'sales-service', 'POST', '/internal/sales/orders/{orderId}/invoice'),
    ('InvoiceController/cancel',          'sales-service', 'POST', '/internal/sales/invoices/{id}/cancellation'),
    ('InvoiceController/replace',         'sales-service', 'POST', '/internal/sales/invoices/{id}/replacement'),
    ('InvoiceRequestController/get',      'sales-service', 'GET',  '/internal/sales/orders/{orderId}/invoice-request'),
    ('InvoiceRequestController/save',     'sales-service', 'PUT',  '/internal/sales/orders/{orderId}/invoice-request'),
    ('InvoiceSettingsController/get',     'sales-service', 'GET',  '/internal/sales/invoice-settings'),
    ('InvoiceSettingsController/update',  'sales-service', 'PUT',  '/internal/sales/invoice-settings'),
    ('ShopInvoiceController/saveRequest', 'sales-service', 'PUT',  '/internal/sales/shop/orders/{id}/invoice-request'),
    -- Goi B: chot ca thu ngan (sales-service, shift.infrastructure.web)
    ('CashierShiftController/open',                 'sales-service', 'POST', '/internal/sales/shifts'),
    ('CashierShiftController/current',              'sales-service', 'GET',  '/internal/sales/shifts/me/current'),
    ('CashierShiftController/mine',                 'sales-service', 'GET',  '/internal/sales/shifts/me'),
    ('CashierShiftController/getMine',              'sales-service', 'GET',  '/internal/sales/shifts/me/{id}'),
    ('CashierShiftController/close',                'sales-service', 'POST', '/internal/sales/shifts/me/current/closing'),
    ('CashierShiftAdminController/search',          'sales-service', 'GET',  '/internal/sales/shifts'),
    ('CashierShiftAdminController/get',             'sales-service', 'GET',  '/internal/sales/shifts/{id}'),
    ('CashierShiftAdminController/closeOnBehalf',   'sales-service', 'POST', '/internal/sales/shifts/{id}/closing'),
    ('CashierShiftAdminController/reopen',          'sales-service', 'POST', '/internal/sales/shifts/{id}/reopening'),
    ('CashierShiftAdminController/approveVariance', 'sales-service', 'POST', '/internal/sales/shifts/{id}/variance-approval'),
    ('CashierShiftSettingsController/get',          'sales-service', 'GET',  '/internal/sales/shift-settings'),
    ('CashierShiftSettingsController/update',       'sales-service', 'PUT',  '/internal/sales/shift-settings'),
    -- Goi C: thong bao (support-service, notification.infrastructure.web); chi dang ky, khong gan mau
    ('NotificationDeliveryController/search',         'support-service', 'GET',  '/internal/notification-deliveries'),
    ('NotificationDeliveryController/retry',          'support-service', 'POST', '/internal/notification-deliveries/{id}/retry'),
    ('NotificationSettingsController/getChannels',    'support-service', 'GET',  '/internal/notification-settings/channels'),
    ('NotificationSettingsController/updateChannels', 'support-service', 'PUT',  '/internal/notification-settings/channels'),
    ('NotificationTemplateController/list',           'support-service', 'GET',  '/internal/notification-templates'),
    ('NotificationTemplateController/get',            'support-service', 'GET',  '/internal/notification-templates/{type}/{channel}'),
    ('NotificationTemplateController/save',           'support-service', 'PUT',  '/internal/notification-templates/{type}/{channel}'),
    ('NotificationTemplateController/preview',        'support-service', 'POST', '/internal/notification-templates/{type}/{channel}/preview'),
    -- Goi F: xuat du lieu tenant (identity-service, ADR-0031); chi dang ky, khong gan mau
    ('TenantDataExportController/request',  'identity-service', 'POST', '/internal/tenant-data/exports'),
    ('TenantDataExportController/list',     'identity-service', 'GET',  '/internal/tenant-data/exports'),
    ('TenantDataExportController/get',      'identity-service', 'GET',  '/internal/tenant-data/exports/{id}'),
    ('TenantDataExportController/download', 'identity-service', 'GET',  '/internal/tenant-data/exports/{id}/download')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Gan mau vai tro (c1 Thu ngan, c3 Quan ly cua hang, c4 Khach hang) =====
INSERT INTO role_template_permissions (template_id, permission_code)
SELECT m.template_id::uuid, m.code
FROM (VALUES
    -- Thu ngan (c1)
    ('00000000-0000-0000-0000-0000000000c1', 'InvoiceController/search'),
    ('00000000-0000-0000-0000-0000000000c1', 'InvoiceController/get'),
    ('00000000-0000-0000-0000-0000000000c1', 'InvoiceController/print'),
    ('00000000-0000-0000-0000-0000000000c1', 'InvoiceController/issue'),
    ('00000000-0000-0000-0000-0000000000c1', 'InvoiceRequestController/get'),
    ('00000000-0000-0000-0000-0000000000c1', 'InvoiceRequestController/save'),
    ('00000000-0000-0000-0000-0000000000c1', 'CashierShiftController/open'),
    ('00000000-0000-0000-0000-0000000000c1', 'CashierShiftController/current'),
    ('00000000-0000-0000-0000-0000000000c1', 'CashierShiftController/mine'),
    ('00000000-0000-0000-0000-0000000000c1', 'CashierShiftController/getMine'),
    ('00000000-0000-0000-0000-0000000000c1', 'CashierShiftController/close'),
    -- Quan ly cua hang (c3): moi khoa A va B
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceController/search'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceController/print'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceController/issue'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceController/cancel'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceController/replace'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceRequestController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceRequestController/save'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceSettingsController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'InvoiceSettingsController/update'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftController/open'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftController/current'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftController/mine'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftController/getMine'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftController/close'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftAdminController/search'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftAdminController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftAdminController/closeOnBehalf'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftAdminController/reopen'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftAdminController/approveVariance'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftSettingsController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'CashierShiftSettingsController/update'),
    -- Khach hang (c4): yeu cau hoa don cho don cua chinh minh
    ('00000000-0000-0000-0000-0000000000c4', 'ShopInvoiceController/saveRequest')
) AS m (template_id, code)
WHERE EXISTS (SELECT 1 FROM role_templates rt WHERE rt.id = m.template_id::uuid)
ON CONFLICT DO NOTHING;
