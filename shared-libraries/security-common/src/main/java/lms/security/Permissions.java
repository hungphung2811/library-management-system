package lms.security;

/** Danh mục permission dùng chung. Kiểm tra quyền luôn theo permission, không theo tên role. */
public final class Permissions {

    private Permissions() {
    }

    public static final String USER_VIEW = "USER_VIEW";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String USER_DELETE = "USER_DELETE";

    public static final String DEPARTMENT_VIEW = "DEPARTMENT_VIEW";
    public static final String DEPARTMENT_CREATE = "DEPARTMENT_CREATE";
    public static final String DEPARTMENT_UPDATE = "DEPARTMENT_UPDATE";

    public static final String ROLE_VIEW = "ROLE_VIEW";
    public static final String ROLE_CREATE = "ROLE_CREATE";
    public static final String ROLE_UPDATE = "ROLE_UPDATE";
    public static final String ROLE_ASSIGN = "ROLE_ASSIGN";

    public static final String BOOK_VIEW = "BOOK_VIEW";
    public static final String BOOK_CREATE = "BOOK_CREATE";
    public static final String BOOK_UPDATE = "BOOK_UPDATE";
    public static final String BOOK_DELETE = "BOOK_DELETE";

    public static final String BORROW_BOOK = "BORROW_BOOK";
    public static final String RETURN_BOOK = "RETURN_BOOK";
    public static final String APPROVE_BORROWING = "APPROVE_BORROWING";

    public static final String AUDIT_VIEW = "AUDIT_VIEW";
}
