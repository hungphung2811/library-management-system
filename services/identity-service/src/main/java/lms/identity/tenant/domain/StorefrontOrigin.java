package lms.identity.tenant.domain;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import lms.identity.tenant.domain.InvalidStorefrontOriginException.Reason;

/**
 * Origin của website storefront đã chuẩn hóa (ADR-0013 2.6): {@code scheme://host[:port]}, scheme và host chữ thường,
 * IDN chuyển sang punycode, bỏ cổng mặc định (80/443) và dấu {@code /} cuối. Từ chối path/query/fragment/userinfo,
 * {@code *}, {@code null}, IPv6, host có dấu chấm cuối. {@code http} chỉ cho localhost/127.0.0.1 hoặc khi
 * {@code allowHttp}.
 */
public final class StorefrontOrigin {

    public static final int MAX_LENGTH = 300;

    private final String value;

    private StorefrontOrigin(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** @throws InvalidStorefrontOriginException với mã lý do ({@link Reason}) */
    public static StorefrontOrigin parse(String raw, boolean allowHttp) {
        if (raw == null) throw new InvalidStorefrontOriginException(Reason.SYNTAX);
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) throw new InvalidStorefrontOriginException(Reason.SYNTAX);
        if (s.contains("*")) throw new InvalidStorefrontOriginException(Reason.WILDCARD);
        if (s.equalsIgnoreCase("null")) throw new InvalidStorefrontOriginException(Reason.NULL);

        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            // tên miền quốc tế (ký tự Unicode) vẫn hợp lệ với URI; lỗi ở đây là ký tự cấm (khoảng trắng, "<"...)
            throw new InvalidStorefrontOriginException(Reason.SYNTAX);
        }
        String scheme = uri.getScheme();
        if (scheme == null) throw new InvalidStorefrontOriginException(Reason.SCHEME);
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new InvalidStorefrontOriginException(Reason.SCHEME);
        if (uri.isOpaque()) throw new InvalidStorefrontOriginException(Reason.SYNTAX);
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new InvalidStorefrontOriginException(Reason.PATH);
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) throw new InvalidStorefrontOriginException(Reason.PATH);

        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) throw new InvalidStorefrontOriginException(Reason.HOST);
        if (authority.contains("[")) throw new InvalidStorefrontOriginException(Reason.HOST); // IPv6
        String host = authority;
        int port = -1;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            String p = authority.substring(colon + 1);
            if (!p.isEmpty()) {
                if (!p.chars().allMatch(Character::isDigit) || p.length() > 5) {
                    throw new InvalidStorefrontOriginException(Reason.PORT);
                }
                port = Integer.parseInt(p);
                if (port < 1 || port > 65535) throw new InvalidStorefrontOriginException(Reason.PORT);
            }
        }
        if (host.isEmpty() || host.contains(":") || host.endsWith(".")) {
            throw new InvalidStorefrontOriginException(Reason.HOST);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new InvalidStorefrontOriginException(Reason.HOST);
        }
        if (!ascii.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || ascii.contains("..") || ascii.length() > 253) {
            throw new InvalidStorefrontOriginException(Reason.HOST);
        }

        boolean local = ascii.equals("localhost") || ascii.equals("127.0.0.1");
        if (scheme.equals("http") && !local && !allowHttp) throw new InvalidStorefrontOriginException(Reason.HTTP_NOT_ALLOWED);
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
        String result = scheme + "://" + ascii + (defaultPort ? "" : ":" + port);
        if (result.length() > MAX_LENGTH) throw new InvalidStorefrontOriginException(Reason.SYNTAX);
        return new StorefrontOrigin(result);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StorefrontOrigin other && other.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
