-- UC01: tài khoản cấp mật khẩu tạm phải đổi mật khẩu trước khi dùng các chức năng khác.
ALTER TABLE credentials ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
