-- Permission cho support-service (nhật ký audit). Không sửa V1-V3 đã chạy.
INSERT INTO permissions (code, description) VALUES
    ('AUDIT_VIEW', 'Xem nhật ký audit');

-- V3 chỉ gán permission có tại thời điểm đó cho SUPER_ADMIN, nên phải gán tường minh ở đây.
INSERT INTO role_permissions (role_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'AUDIT_VIEW'),
    ('00000000-0000-0000-0000-0000000000a2', 'AUDIT_VIEW');
