package lms.imports.core;

/** Thông tin đăng nhập tạm sinh khi confirm (chỉ identity). Chỉ lưu ở khóa {@code creds}, tải một lần. */
public record IssuedCredential(String login, String secret) {

    @Override
    public String toString() {
        return "IssuedCredential[login=" + login + ", secret=***]";
    }
}
