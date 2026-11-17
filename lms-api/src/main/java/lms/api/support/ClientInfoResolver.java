package lms.api.support;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Xác định IP client: chỉ tin {@code X-Real-IP} khi địa chỉ kết nối tới thuộc danh sách proxy tin cậy, ngược lại dùng
 * địa chỉ kết nối. KHÔNG đọc {@code X-Forwarded-For} (client nối thêm được). User-agent cắt còn 512 ký tự.
 */
public class ClientInfoResolver {

    static final String REAL_IP_HEADER = "X-Real-IP";
    static final int MAX_USER_AGENT = 512;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final List<Cidr> trusted;

    public ClientInfoResolver(ClientIpProperties properties) {
        List<Cidr> list = new ArrayList<>();
        for (String entry : properties.trustedProxies()) {
            list.add(Cidr.parse(entry));
        }
        this.trusted = List.copyOf(list);
    }

    /** Thông tin của request đang xử lý trên luồng này; {@link ClientInfo#NONE} khi không có request. */
    public ClientInfo current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return resolve(attributes.getRequest());
        }
        return ClientInfo.NONE;
    }

    public ClientInfo resolve(HttpServletRequest request) {
        String remote = literal(request.getRemoteAddr());
        String ip = remote;
        if (remote != null && isTrusted(remote)) {
            String real = literal(request.getHeader(REAL_IP_HEADER));
            if (real != null) {
                ip = real;
            }
        }
        return new ClientInfo(ip, userAgent(request.getHeader("User-Agent")));
    }

    private boolean isTrusted(String ip) {
        InetAddress address = parse(ip);
        if (address == null) return false;
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) return true;
        }
        return false;
    }

    private static String userAgent(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_USER_AGENT));
        for (int i = 0; i < raw.length() && sb.length() < MAX_USER_AGENT; i++) {
            char c = raw.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        String value = sb.toString().strip();
        return value.isEmpty() ? null : value;
    }

    /** Chỉ nhận chuỗi là IP dạng chữ số (tránh tra DNS); sai định dạng thì null. */
    private static String literal(String value) {
        if (value == null) return null;
        String v = value.strip();
        return parse(v) == null ? null : v;
    }

    private static InetAddress parse(String v) {
        if (v == null) return null;
        boolean ok = IPV4.matcher(v).matches() || (v.indexOf(':') >= 0 && IPV6.matcher(v).matches());
        if (!ok) return null;
        try {
            return InetAddress.getByName(v);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String entry) {
            String text = entry.strip();
            int slash = text.indexOf('/');
            String host = slash < 0 ? text : text.substring(0, slash);
            InetAddress address = ClientInfoResolver.parse(host);
            if (address == null) {
                throw new IllegalArgumentException("lms.api.client-ip.trusted-proxies: giá trị không hợp lệ: " + entry);
            }
            byte[] bytes = address.getAddress();
            int max = bytes.length * 8;
            int prefix = slash < 0 ? max : Integer.parseInt(text.substring(slash + 1).strip());
            if (prefix < 0 || prefix > max) {
                throw new IllegalArgumentException("lms.api.client-ip.trusted-proxies: tiền tố sai: " + entry);
            }
            return new Cidr(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] other = address.getAddress();
            if (other.length != network.length) return false;
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (other[i] != network[i]) return false;
            }
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (other[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
