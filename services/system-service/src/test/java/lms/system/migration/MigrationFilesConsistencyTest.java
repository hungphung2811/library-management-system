package lms.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Kiểm tra tĩnh các file migration trong mọi target (không cần DB): không trùng số, số tăng liên tục (trừ khoảng trống đã
 * biết và có chủ đích), khóa quyền seed ở identity V34/V35/V36 nhất quán với mọi file khác (cùng một khóa phải cùng
 * service/method/path), mọi khóa gán cho role template đều đã được đăng ký ở permissions.
 */
class MigrationFilesConsistencyTest {

    private static final Pattern FILE = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final Pattern ROW = Pattern.compile(
            "\\('([A-Za-z]+/[A-Za-z]+)',\\s*'([a-z-]+-service)',\\s*'([A-Z]+)',\\s*'([^']+)'\\)");
    private static final Pattern TEMPLATE_ROW = Pattern.compile(
            "\\('00000000-0000-0000-0000-0000000000[a-f0-9]{2}'(?:::uuid)?,\\s*'([A-Za-z]+/[A-Za-z]+)'\\)"
            + "|\\('([A-Za-z]+/[A-Za-z]+)'\\)");
    /** Khoảng trống có chủ đích (ghi trong header của migration kế tiếp). Thêm vào đây nếu thật sự cố ý. */
    private static final Map<String, Set<Integer>> KNOWN_GAPS = Map.of("identity", Set.of(33));

    private static final Set<String> NEW_CODES = Set.of("WorkController/search", "WorkController/get",
            "WorkController/create", "WorkController/update", "WorkController/merge", "TitleController/changeWork",
            "SeriesController/search", "SeriesController/options", "SeriesController/get", "SeriesController/create",
            "SeriesController/update", "SeriesController/setStatus", "SeriesImportController/upload",
            "SeriesImportController/confirm", "BorrowingController/reportFound");

    private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

    private static Map<Integer, String> versions(String target) throws Exception {
        Map<Integer, String> out = new TreeMap<>();
        for (Resource r : RESOLVER.getResources("classpath*:db/migration/" + target + "/V*__*.sql")) {
            String name = r.getFilename();
            Matcher m = FILE.matcher(name);
            assertThat(m.matches()).as("tên file migration hợp lệ: " + name).isTrue();
            int v = Integer.parseInt(m.group(1));
            String previous = out.put(v, name);
            assertThat(previous).as("trùng số V" + v + " trong " + target + ": " + previous + " và " + name).isNull();
        }
        return out;
    }

    private static String read(String target, String file) throws Exception {
        Resource[] found = RESOLVER.getResources("classpath*:db/migration/" + target + "/" + file);
        assertThat(found).as(file).hasSize(1);
        try (var in = found[0].getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void khongTrungSoVaSoTangLienTucTrongMoiTarget() throws Exception {
        for (String target : List.of("identity", "catalog", "circulation", "audit", "system", "sales")) {
            Map<Integer, String> v = versions(target);
            assertThat(v).as(target).isNotEmpty();
            int max = v.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            assertThat(v).as(target + " bắt đầu từ V1").containsKey(1);
            List<Integer> missing = new ArrayList<>();
            for (int i = 1; i <= max; i++) {
                if (!v.containsKey(i)) missing.add(i);
            }
            Set<Integer> allowed = KNOWN_GAPS.getOrDefault(target, Set.of());
            assertThat(missing).as("khoảng trống số migration của " + target + " (Flyway outOfOrder=false: số bị bỏ trống "
                    + "mà sau này chèn vào sẽ làm validate fail trên DB đã chạy V cao hơn)").allMatch(allowed::contains);
        }
    }

    @Test
    void identityV32DenV37DungThuTuVaTenDaChot() throws Exception {
        Map<Integer, String> v = versions("identity");
        assertThat(v.get(32)).isEqualTo("V32__applications.sql");
        assertThat(v.get(34)).isEqualTo("V34__work_series_permissions.sql");
        assertThat(v.get(35)).isEqualTo("V35__compensation_permissions.sql");
        assertThat(v.get(36)).isEqualTo("V36__series_import_permissions.sql");
        assertThat(v.keySet()).containsAll(List.of(32, 34, 35, 36, 37));
    }

    @Test
    void khoaQuyenNhatQuanGiuaCacFileVaMoiKhoaGanMauDeuDaDangKy() throws Exception {
        Map<String, String> definition = new HashMap<>();
        Map<String, String> definedIn = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        Set<String> registered = new java.util.HashSet<>();
        Map<Integer, String> v = versions("identity");
        // Duyệt theo thứ tự số: khóa đăng ký ở file nào cũng phải cùng service/method/path với file khác
        for (Map.Entry<Integer, String> e : v.entrySet()) {
            String sql = read("identity", e.getValue());
            Matcher m = ROW.matcher(sql);
            while (m.find()) {
                String code = m.group(1);
                String def = m.group(2) + " " + m.group(3) + " " + m.group(4);
                String old = definition.putIfAbsent(code, def);
                // V17 vs V25 có sẵn lệch /api vs /internal của TitleController/lookup (di sản, ngoài phạm vi): chỉ kiểm khóa của V34-V36
                boolean mine = e.getValue().startsWith("V34__") || e.getValue().startsWith("V35__")
                        || e.getValue().startsWith("V36__") || (old != null && NEW_CODES.contains(code));
                if (old != null && !old.equals(def) && mine) {
                    conflicts.add(code + ": " + definedIn.get(code) + " = [" + old + "] nhưng " + e.getValue() + " = [" + def + "]");
                }
                definedIn.putIfAbsent(code, e.getValue());
                registered.add(code);
            }
        }
        assertThat(conflicts).as("cùng một khóa mà khác service/method/path giữa các migration").isEmpty();

        for (String file : List.of("V34__work_series_permissions.sql", "V35__compensation_permissions.sql",
                "V36__series_import_permissions.sql")) {
            String sql = read("identity", file);
            Matcher t = TEMPLATE_ROW.matcher(sql);
            int count = 0;
            while (t.find()) {
                count++;
                String code = t.group(1) != null ? t.group(1) : t.group(2);
                assertThat(registered).as(file + " gán khóa chưa đăng ký ở permissions: " + code).contains(code);
            }
            assertThat(count).as(file + " có gán role template").isPositive();
        }
    }

    @Test
    void v34V35V36KhongDungLaiKhoaCuaNhauVaKhongPhaKhoaCu() throws Exception {
        Map<String, Set<String>> codesByFile = new TreeMap<>();
        for (String file : List.of("V34__work_series_permissions.sql", "V35__compensation_permissions.sql",
                "V36__series_import_permissions.sql")) {
            Set<String> codes = new java.util.TreeSet<>();
            Matcher m = ROW.matcher(read("identity", file));
            while (m.find()) codes.add(m.group(1));
            codesByFile.put(file, codes);
        }
        assertThat(codesByFile.get("V34__work_series_permissions.sql")).hasSize(12);
        assertThat(codesByFile.get("V35__compensation_permissions.sql")).hasSize(14);
        assertThat(codesByFile.get("V36__series_import_permissions.sql")).hasSize(2);
        Set<String> all = new java.util.HashSet<>();
        int total = 0;
        for (Set<String> s : codesByFile.values()) {
            total += s.size();
            all.addAll(s);
        }
        assertThat(all).as("không khóa nào được đăng ký ở hai migration mới").hasSize(total);
    }
}
