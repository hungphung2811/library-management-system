-- Phòng ban là dữ liệu của tenant: chỉ còn ở tenant_memberships.department_id. Bỏ cột toàn cục users.department_id.
-- V11 đã chép users.department_id sang membership của tenant mặc định; script này bù cho người dùng tạo sau V11
-- (code trước Task 5 chỉ ghi users.department_id, membership tạo kèm credential có department_id NULL).

-- 1) Người dùng chỉ có bản ghi users mà chưa có membership nào: thuộc tenant mặc định (trước khi có API tạo tenant
--    mọi dữ liệu đều ở tenant mặc định).
INSERT INTO tenant_memberships (user_id, tenant_id, department_id, status)
SELECT u.id, '00000000-0000-0000-0000-0000000000d1', NULL, 'ACTIVE'
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM tenant_memberships m WHERE m.user_id = u.id)
ON CONFLICT DO NOTHING;

-- 2) Chép phòng ban sang membership còn trống, chỉ khi phòng ban thuộc đúng tenant của membership. departments bật
--    FORCE RLS (áp cả chủ bảng, m4): đặt app.tenant_id lần lượt cho từng tenant trong transaction của migration
--    (is_local = true). Chạy bằng superuser thì RLS bị bỏ qua, điều kiện d.tenant_id = m.tenant_id vẫn giữ đúng.
DO $$
DECLARE
    t UUID;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        UPDATE tenant_memberships m
        SET department_id = u.department_id
        FROM users u, departments d
        WHERE u.id = m.user_id
          AND d.id = u.department_id
          AND d.tenant_id = m.tenant_id
          AND m.tenant_id = t
          AND m.department_id IS NULL
          AND u.department_id IS NOT NULL;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- 3) Bỏ cột (kèm chỉ mục idx_users_department và khóa ngoại sang departments)
DROP INDEX IF EXISTS idx_users_department;
ALTER TABLE users DROP COLUMN department_id;
