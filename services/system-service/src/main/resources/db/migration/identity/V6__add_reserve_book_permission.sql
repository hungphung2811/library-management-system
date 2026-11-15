-- Permission đặt trước sách (UC03). Không sửa V1-V5 đã chạy.
INSERT INTO permissions (code, description) VALUES
    ('RESERVE_BOOK', 'Đặt trước và hủy đặt trước sách');

INSERT INTO role_permissions (role_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'RESERVE_BOOK'),
    ('00000000-0000-0000-0000-0000000000a2', 'RESERVE_BOOK'),
    ('00000000-0000-0000-0000-0000000000a5', 'RESERVE_BOOK');
