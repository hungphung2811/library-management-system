package lms.identity.apiclient.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Site key công khai của Application PUBLIC_SITE (D4): {@code lms_pk_} + base64url(24 byte CSPRNG). Là định danh công
 * khai (lưu nguyên văn, UNIQUE), KHÔNG phải bằng chứng xác thực; khớp CHECK ck_applications_site_key_format.
 */
public final class SiteKey {

    public static final String PREFIX = "lms_pk_";
    private static final int BYTES = 24;
    private static final Pattern FORMAT = Pattern.compile("^lms_pk_[A-Za-z0-9_-]{32,56}$");

    private SiteKey() {
    }

    public static String generate(SecureRandom random) {
        byte[] raw = new byte[BYTES];
        random.nextBytes(raw);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
