package lms.security;

/** Tên claim trong access token do auth-service phát hành. */
public final class JwtClaims {

    private JwtClaims() {
    }

    public static final String USERNAME = "username";
    public static final String ROLES = "roles";
    public static final String PERMISSIONS = "permissions";
    public static final String DEPARTMENT_ID = "departmentId";
}
