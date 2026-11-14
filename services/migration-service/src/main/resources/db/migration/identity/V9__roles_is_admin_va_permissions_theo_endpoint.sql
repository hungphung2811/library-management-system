-- Role quản trị: cờ is_admin nằm trong dữ liệu, không gắn với tên role nào. Người dùng có ít nhất một role
-- is_admin được bỏ qua kiểm tra permission theo endpoint.
ALTER TABLE roles ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- Role SUPER_ADMIN đã seed ở V3 được đánh dấu quản trị (dữ liệu, không phải logic trong code).
UPDATE roles SET is_admin = TRUE WHERE id = '00000000-0000-0000-0000-0000000000a1';

-- Khóa permission tự sinh theo endpoint (TênController/tênPhươngThức) dài hơn mã tự đặt.
ALTER TABLE permissions ALTER COLUMN code TYPE VARCHAR(200);
ALTER TABLE role_permissions ALTER COLUMN permission_code TYPE VARCHAR(200);

-- Nguồn và thông tin endpoint của khóa: AUTO = tự sinh khi endpoint được gọi lần đầu, CUSTOM = mã BE đặt.
-- Các mã seed sẵn là CUSTOM; thông tin endpoint (service, method, path) được điền khi endpoint tương ứng được gọi.
ALTER TABLE permissions ADD COLUMN source        VARCHAR(10) NOT NULL DEFAULT 'CUSTOM';
ALTER TABLE permissions ADD COLUMN service       VARCHAR(100);
ALTER TABLE permissions ADD COLUMN http_method   VARCHAR(10);
ALTER TABLE permissions ADD COLUMN path_pattern  VARCHAR(500);
ALTER TABLE permissions ADD COLUMN first_seen_at TIMESTAMP WITH TIME ZONE;

-- ===== Chuyển mã permission seed cũ (BORROW_BOOK, ROLE_VIEW...) sang khóa theo endpoint (Controller/method) =====
-- Mỗi mã cũ ứng với một hoặc nhiều endpoint; role nào đang có mã cũ sẽ được cấp các khóa endpoint tương ứng,
-- nên quyền hiệu lực của các role không đổi. Sau đó xóa mã cũ (không còn endpoint nào kiểm theo mã cũ).
CREATE TEMPORARY TABLE permission_key_map (
    old_code     VARCHAR(100),
    new_key      VARCHAR(200),
    service      VARCHAR(100),
    http_method  VARCHAR(10),
    path_pattern VARCHAR(500)
) ON COMMIT DROP;

INSERT INTO permission_key_map (old_code, new_key, service, http_method, path_pattern) VALUES
    ('USER_CREATE',       'AuthController/register',                   'identity-service',    'POST',   '/api/auth/credentials'),
    ('ROLE_CREATE',       'AccessControlController/createPermission',  'identity-service',    'POST',   '/api/permissions'),
    ('ROLE_CREATE',       'AccessControlController/createRole',        'identity-service',    'POST',   '/api/roles'),
    ('ROLE_VIEW',         'AccessControlController/listPermissions',   'identity-service',    'GET',    '/api/permissions'),
    ('ROLE_VIEW',         'AccessControlController/listRoles',         'identity-service',    'GET',    '/api/roles'),
    ('ROLE_VIEW',         'AccessControlController/getRole',           'identity-service',    'GET',    '/api/roles/{roleId}'),
    ('ROLE_VIEW',         'AccessControlController/getUserAccess',     'identity-service',    'GET',    '/api/access/users/{userId}'),
    ('ROLE_UPDATE',       'AccessControlController/grantPermission',   'identity-service',    'POST',   '/api/roles/{roleId}/permissions'),
    ('ROLE_UPDATE',       'AccessControlController/revokePermission',  'identity-service',    'DELETE', '/api/roles/{roleId}/permissions/**'),
    ('ROLE_ASSIGN',       'AccessControlController/assignRole',        'identity-service',    'POST',   '/api/access/users/{userId}/roles'),
    ('ROLE_ASSIGN',       'AccessControlController/revokeRole',        'identity-service',    'DELETE', '/api/access/users/{userId}/roles/{roleId}'),
    ('USER_VIEW',         'ReaderProfileController/get',               'identity-service',    'GET',    '/api/users/{userId}/reader-profile'),
    ('USER_CREATE',       'ReaderProfileController/issue',             'identity-service',    'POST',   '/api/users/{userId}/reader-profile'),
    ('USER_UPDATE',       'ReaderProfileController/renew',             'identity-service',    'PUT',    '/api/users/{userId}/reader-profile/card-expiry'),
    ('USER_UPDATE',       'ReaderProfileController/changeStatus',      'identity-service',    'PUT',    '/api/users/{userId}/reader-profile/status'),
    ('BOOK_CREATE',       'TitleController/create',                    'catalog-service',     'POST',   '/api/books'),
    ('BOOK_VIEW',         'TitleController/search',                    'catalog-service',     'GET',    '/api/books'),
    ('BOOK_VIEW',         'TitleController/get',                       'catalog-service',     'GET',    '/api/books/{id}'),
    ('BOOK_CREATE',       'BookCopyController/add',                    'catalog-service',     'POST',   '/api/book-copies'),
    ('BOOK_VIEW',         'BookCopyController/list',                   'catalog-service',     'GET',    '/api/book-copies'),
    ('BORROW_BOOK',       'BorrowingController/borrow',                'circulation-service', 'POST',   '/api/borrowings'),
    ('BORROW_BOOK',       'BorrowingController/renew',                 'circulation-service', 'POST',   '/api/borrowings/{id}/renewals'),
    ('RETURN_BOOK',       'BorrowingController/returnBook',            'circulation-service', 'POST',   '/api/borrowings/{id}/returns'),
    ('RESERVE_BOOK',      'ReservationController/reserve',             'circulation-service', 'POST',   '/api/reservations'),
    ('RESERVE_BOOK',      'ReservationController/mine',                'circulation-service', 'GET',    '/api/reservations'),
    ('RESERVE_BOOK',      'ReservationController/cancel',              'circulation-service', 'DELETE', '/api/reservations/{id}'),
    ('APPROVE_BORROWING', 'ReservationController/expireNow',           'circulation-service', 'POST',   '/api/reservations/expirations'),
    ('AUDIT_VIEW',        'AuditLogController/search',                 'support-service',     'GET',    '/api/audit-logs');

INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT DISTINCT m.new_key, m.http_method || ' ' || m.path_pattern || ' (' || m.service || ')', 'AUTO',
       m.service, m.http_method, m.path_pattern, now()
FROM permission_key_map m
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT DISTINCT rp.role_id, m.new_key
FROM role_permissions rp
JOIN permission_key_map m ON m.old_code = rp.permission_code
ON CONFLICT DO NOTHING;

-- Xóa toàn bộ mã seed cũ: các mã chưa có endpoint (USER_DELETE, DEPARTMENT_*, BOOK_UPDATE, BOOK_DELETE) sẽ
-- được tạo lại dưới dạng khóa endpoint khi endpoint tương ứng được viết và gọi lần đầu.
DELETE FROM role_permissions WHERE permission_code IN (
    'USER_VIEW', 'USER_CREATE', 'USER_UPDATE', 'USER_DELETE',
    'DEPARTMENT_VIEW', 'DEPARTMENT_CREATE', 'DEPARTMENT_UPDATE',
    'ROLE_VIEW', 'ROLE_CREATE', 'ROLE_UPDATE', 'ROLE_ASSIGN',
    'BOOK_VIEW', 'BOOK_CREATE', 'BOOK_UPDATE', 'BOOK_DELETE',
    'BORROW_BOOK', 'RETURN_BOOK', 'APPROVE_BORROWING', 'AUDIT_VIEW', 'RESERVE_BOOK');
DELETE FROM permissions WHERE code IN (
    'USER_VIEW', 'USER_CREATE', 'USER_UPDATE', 'USER_DELETE',
    'DEPARTMENT_VIEW', 'DEPARTMENT_CREATE', 'DEPARTMENT_UPDATE',
    'ROLE_VIEW', 'ROLE_CREATE', 'ROLE_UPDATE', 'ROLE_ASSIGN',
    'BOOK_VIEW', 'BOOK_CREATE', 'BOOK_UPDATE', 'BOOK_DELETE',
    'BORROW_BOOK', 'RETURN_BOOK', 'APPROVE_BORROWING', 'AUDIT_VIEW', 'RESERVE_BOOK');
