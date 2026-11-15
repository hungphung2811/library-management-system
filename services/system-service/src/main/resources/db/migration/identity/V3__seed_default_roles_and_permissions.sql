INSERT INTO permissions (code, description) VALUES
    ('USER_VIEW', 'Xem người dùng'),
    ('USER_CREATE', 'Tạo người dùng'),
    ('USER_UPDATE', 'Cập nhật người dùng'),
    ('USER_DELETE', 'Xóa hoặc vô hiệu hóa người dùng'),
    ('DEPARTMENT_VIEW', 'Xem phòng ban'),
    ('DEPARTMENT_CREATE', 'Tạo phòng ban'),
    ('DEPARTMENT_UPDATE', 'Cập nhật phòng ban'),
    ('ROLE_VIEW', 'Xem role và permission'),
    ('ROLE_CREATE', 'Tạo role và permission'),
    ('ROLE_UPDATE', 'Cập nhật role'),
    ('ROLE_ASSIGN', 'Gán hoặc thu hồi role của người dùng'),
    ('BOOK_VIEW', 'Xem sách'),
    ('BOOK_CREATE', 'Thêm sách'),
    ('BOOK_UPDATE', 'Cập nhật sách'),
    ('BOOK_DELETE', 'Xóa sách'),
    ('BORROW_BOOK', 'Mượn và gia hạn sách'),
    ('RETURN_BOOK', 'Trả sách'),
    ('APPROVE_BORROWING', 'Duyệt yêu cầu mượn');

INSERT INTO roles (id, name, description) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'SUPER_ADMIN', 'Toàn quyền hệ thống'),
    ('00000000-0000-0000-0000-0000000000a2', 'LIBRARY_ADMIN', 'Quản trị thư viện'),
    ('00000000-0000-0000-0000-0000000000a3', 'LIBRARIAN', 'Thủ thư'),
    ('00000000-0000-0000-0000-0000000000a4', 'DEPARTMENT_MANAGER', 'Quản lý phòng ban'),
    ('00000000-0000-0000-0000-0000000000a5', 'MEMBER', 'Thành viên thư viện');

-- SUPER_ADMIN: tất cả permission
INSERT INTO role_permissions (role_id, permission_code)
SELECT '00000000-0000-0000-0000-0000000000a1', code FROM permissions;

-- LIBRARY_ADMIN: tất cả trừ xóa người dùng
INSERT INTO role_permissions (role_id, permission_code)
SELECT '00000000-0000-0000-0000-0000000000a2', code FROM permissions WHERE code <> 'USER_DELETE';

INSERT INTO role_permissions (role_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000a3', 'USER_VIEW'),
    ('00000000-0000-0000-0000-0000000000a3', 'BOOK_VIEW'),
    ('00000000-0000-0000-0000-0000000000a3', 'BOOK_CREATE'),
    ('00000000-0000-0000-0000-0000000000a3', 'BOOK_UPDATE'),
    ('00000000-0000-0000-0000-0000000000a3', 'BORROW_BOOK'),
    ('00000000-0000-0000-0000-0000000000a3', 'RETURN_BOOK'),
    ('00000000-0000-0000-0000-0000000000a3', 'APPROVE_BORROWING'),
    ('00000000-0000-0000-0000-0000000000a4', 'USER_VIEW'),
    ('00000000-0000-0000-0000-0000000000a4', 'DEPARTMENT_VIEW'),
    ('00000000-0000-0000-0000-0000000000a4', 'BOOK_VIEW'),
    ('00000000-0000-0000-0000-0000000000a4', 'APPROVE_BORROWING'),
    ('00000000-0000-0000-0000-0000000000a5', 'BOOK_VIEW'),
    ('00000000-0000-0000-0000-0000000000a5', 'BORROW_BOOK');

-- Tài khoản quản trị khởi tạo (userId cố định, auth-service tạo credential tương ứng khi bootstrap)
INSERT INTO user_roles (user_id, role_id) VALUES
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1');
