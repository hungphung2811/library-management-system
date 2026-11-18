package lms.catalog.catalog.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Thông tin bổ sung tùy chọn của đầu sách (phụ đề, năm xuất bản, lần tái bản, ngôn ngữ, số trang, định dạng, dịch giả,
 * bộ sách, ảnh bìa, nhãn). Khi dựng thì chuẩn hóa (trim, rỗng thành null, ngôn ngữ chữ thường, nhãn bỏ trùng không
 * phân biệt hoa thường) và kiểm quy tắc, sai thì ném {@link InvalidTitleException}. Bất biến.
 */
public record TitleExtras(String subtitle, Integer publicationYear, String edition, String language, Integer pageCount,
                          TitleFormat format, String translator, String series, Integer seriesNumber,
                          String coverImageUrl, List<String> tags) {

    public static final int MAX_SUBTITLE = 255;
    public static final int MAX_EDITION = 50;
    public static final int MAX_TRANSLATOR = 255;
    public static final int MAX_SERIES = 255;
    public static final int MAX_COVER_URL = 500;
    public static final int MIN_YEAR = 1000;
    public static final int MAX_PAGES = 20000;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG_LENGTH = 50;

    public static final TitleExtras EMPTY = new TitleExtras(null, null, null, null, null, null, null, null, null, null,
            List.of());

    public TitleExtras {
        subtitle = text(subtitle, MAX_SUBTITLE, "Phụ đề");
        edition = text(edition, MAX_EDITION, "Lần tái bản");
        translator = text(translator, MAX_TRANSLATOR, "Dịch giả");
        series = text(series, MAX_SERIES, "Bộ sách");
        if (publicationYear != null && (publicationYear < MIN_YEAR || publicationYear > Year.now().getValue() + 1)) {
            throw new InvalidTitleException("Năm xuất bản phải từ " + MIN_YEAR + " đến " + (Year.now().getValue() + 1));
        }
        language = language(language);
        if (pageCount != null && (pageCount <= 0 || pageCount > MAX_PAGES)) {
            throw new InvalidTitleException("Số trang phải từ 1 đến " + MAX_PAGES);
        }
        if (seriesNumber != null && seriesNumber <= 0) throw new InvalidTitleException("Tập thứ phải > 0");
        if (seriesNumber != null && series == null) {
            throw new InvalidTitleException("Tập thứ chỉ hợp lệ khi có bộ sách");
        }
        coverImageUrl = coverUrl(coverImageUrl);
        tags = tags(tags);
    }

    public boolean isEmpty() {
        return equals(EMPTY);
    }

    private static String text(String raw, int max, String field) {
        if (raw == null) return null;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > max) throw new InvalidTitleException(field + " tối đa " + max + " ký tự");
        return s;
    }

    private static String language(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (!s.matches("[a-z]{2,8}")) {
            throw new InvalidTitleException("Ngôn ngữ phải gồm 2 đến 8 chữ cái thường (vi, en, fr...)");
        }
        return s;
    }

    private static String coverUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip();
        if (s.length() > MAX_COVER_URL) throw new InvalidTitleException("Ảnh bìa tối đa " + MAX_COVER_URL + " ký tự");
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new InvalidTitleException("Ảnh bìa phải là URL http hoặc https");
            }
        } catch (URISyntaxException e) {
            throw new InvalidTitleException("Ảnh bìa không phải URL hợp lệ");
        }
        return s;
    }

    private static List<String> tags(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String t : raw) {
            String s = text(t, MAX_TAG_LENGTH, "Nhãn");
            if (s != null && seen.add(s.toLowerCase(Locale.ROOT))) result.add(s);
        }
        if (result.size() > MAX_TAGS) throw new InvalidTitleException("Tối đa " + MAX_TAGS + " nhãn");
        return List.copyOf(result);
    }
}
