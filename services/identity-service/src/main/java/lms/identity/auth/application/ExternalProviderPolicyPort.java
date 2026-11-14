package lms.identity.auth.application;

public interface ExternalProviderPolicyPort {

    /** Tên miền email được phép tự liên kết; danh sách rỗng nghĩa là không giới hạn. */
    boolean isEmailAllowed(String provider, String email);
}
