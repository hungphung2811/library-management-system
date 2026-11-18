package lms.catalog.catalog.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Year;

/**
 * Thông tin bổ sung tùy chọn của mục danh mục, mỗi loại dùng một nhóm trường riêng: tác giả (birthYear, deathYear,
 * nationality, photoUrl, website), nhà xuất bản (country, foundedYear, website, address), vị trí kệ (floor, zone,
 * capacity); thể loại không có. Trường không thuộc loại của mục thì bị từ chối. {@link #normalized} cắt khoảng trắng,
 * rỗng thành null và kiểm quy tắc, sai thì ném {@link InvalidReferenceException}. Bất biến.
 */
public record ReferenceExtras(Integer birthYear, Integer deathYear, String nationality, String photoUrl, String website,
                              String country, Integer foundedYear, String address, String floor, String zone,
                              Integer capacity) {

    public static final ReferenceExtras EMPTY = new ReferenceExtras(null, null, null, null, null, null, null, null,
            null, null, null);

    public static final int MAX_URL = 500;
    public static final int MIN_FOUNDED_YEAR = 1400;
    public static final int MAX_CAPACITY = 100_000;

    public static ReferenceExtras author(Integer birthYear, Integer deathYear, String nationality, String photoUrl,
                                         String website) {
        return new ReferenceExtras(birthYear, deathYear, nationality, photoUrl, website, null, null, null, null, null,
                null);
    }

    public static ReferenceExtras publisher(String country, Integer foundedYear, String website, String address) {
        return new ReferenceExtras(null, null, null, null, website, country, foundedYear, address, null, null, null);
    }

    public static ReferenceExtras shelf(String floor, String zone, Integer capacity) {
        return new ReferenceExtras(null, null, null, null, null, null, null, null, floor, zone, capacity);
    }

    public boolean isEmpty() {
        return equals(EMPTY);
    }

    /** Bản đã chuẩn hóa và kiểm theo loại. */
    public ReferenceExtras normalized(ReferenceKind kind) {
        boolean author = kind == ReferenceKind.AUTHOR;
        boolean publisher = kind == ReferenceKind.PUBLISHER;
        boolean shelf = kind == ReferenceKind.SHELF_LOCATION;
        reject(kind, !author && (birthYear != null || deathYear != null || nationality != null || photoUrl != null),
                "Năm sinh, năm mất, quốc tịch, ảnh chỉ dùng cho tác giả");
        reject(kind, !publisher && (country != null || foundedYear != null || address != null),
                "Quốc gia, năm thành lập, địa chỉ chỉ dùng cho nhà xuất bản");
        reject(kind, !author && !publisher && website != null, "Website chỉ dùng cho tác giả và nhà xuất bản");
        reject(kind, !shelf && (floor != null || zone != null || capacity != null),
                "Tầng, khu, sức chứa chỉ dùng cho vị trí kệ");
        int now = Year.now().getValue();
        if (birthYear != null && (birthYear < 1 || birthYear > now)) {
            throw new InvalidReferenceException(kind, "Năm sinh phải từ 1 đến " + now);
        }
        if (deathYear != null && (deathYear > now || (birthYear != null && deathYear < birthYear)
                || (birthYear == null && deathYear < 1))) {
            throw new InvalidReferenceException(kind, "Năm mất phải từ năm sinh đến " + now);
        }
        if (foundedYear != null && (foundedYear < MIN_FOUNDED_YEAR || foundedYear > now)) {
            throw new InvalidReferenceException(kind, "Năm thành lập phải từ " + MIN_FOUNDED_YEAR + " đến " + now);
        }
        if (capacity != null && (capacity < 1 || capacity > MAX_CAPACITY)) {
            throw new InvalidReferenceException(kind, "Sức chứa phải từ 1 đến " + MAX_CAPACITY);
        }
        return new ReferenceExtras(birthYear, deathYear, text(kind, nationality, 100, "Quốc tịch"),
                url(kind, photoUrl, "Ảnh"), url(kind, website, "Website"), text(kind, country, 100, "Quốc gia"),
                foundedYear, text(kind, address, 500, "Địa chỉ"), text(kind, floor, 20, "Tầng"),
                text(kind, zone, 50, "Khu"), capacity);
    }

    private static void reject(ReferenceKind kind, boolean bad, String message) {
        if (bad) throw new InvalidReferenceException(kind, message);
    }

    private static String text(ReferenceKind kind, String raw, int max, String label) {
        if (raw == null) return null;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > max) {
            throw new InvalidReferenceException(kind, label + " tối đa " + max + " ký tự");
        }
        return s;
    }

    private static String url(ReferenceKind kind, String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip();
        if (s.length() > MAX_URL) throw new InvalidReferenceException(kind, label + " tối đa " + MAX_URL + " ký tự");
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new InvalidReferenceException(kind, label + " phải là URL http hoặc https");
            }
        } catch (URISyntaxException e) {
            throw new InvalidReferenceException(kind, label + " không phải URL hợp lệ");
        }
        return s;
    }
}
