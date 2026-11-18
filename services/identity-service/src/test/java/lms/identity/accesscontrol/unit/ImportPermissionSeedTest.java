package lms.identity.accesscontrol.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Cấu trúc của migration V31__import_permissions.sql (ADR-0024 mục 4.1): đủ 17 khóa import + lookupByIsbn, service đúng
 * theo controller, upload và confirm luôn đi cùng nhau ở mọi vai trò mẫu, ma trận gán đúng bảng ADR, không gán khóa
 * chưa khai. Đối chiếu với controller THẬT của identity nằm ở UserImportControllerSecurityTest; catalog/sales do test của
 * service đó và lms-api chịu trách nhiệm.
 */
class ImportPermissionSeedTest {

    private static final String LIBRARY_ADMIN = "00000000-0000-0000-0000-0000000000a2";
    private static final String LIBRARIAN = "00000000-0000-0000-0000-0000000000a3";
    private static final String STOCK_KEEPER = "00000000-0000-0000-0000-0000000000c2";
    private static final String STORE_MANAGER = "00000000-0000-0000-0000-0000000000c3";

    private static final Pattern PERMISSION_ROW = Pattern.compile(
            "\\('([A-Za-z]+/[A-Za-z]+)',\\s*'([a-z-]+)',\\s*'([A-Z]+)',\\s*'([^']+)'\\)");
    private static final Pattern GRANT_ROW = Pattern.compile("\\('(0{8}-0{4}-0{4}-0{4}-0{10}[a-c][0-9])',\\s*'([A-Za-z]+/[A-Za-z]+)'\\)");

    private static String sql() throws Exception {
        var found = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/identity/V31__import_permissions.sql");
        assertThat(found).hasSize(1);
        try (var in = found[0].getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String[]> permissions(String sql) {
        Map<String, String[]> out = new TreeMap<>();
        Matcher m = PERMISSION_ROW.matcher(sql);
        while (m.find()) out.put(m.group(1), new String[] {m.group(2), m.group(3), m.group(4)});
        return out;
    }

    private static Map<String, Set<String>> grants(String sql) {
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher m = GRANT_ROW.matcher(sql);
        while (m.find()) out.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));
        return out;
    }

    @Test
    void seeds17ImportKeysPlusLookupByIsbnWithTheRightServiceMethodAndPath() throws Exception {
        Map<String, String[]> p = permissions(sql());
        assertThat(p).hasSize(18);
        assertThat(p.keySet().stream().filter(k -> k.contains("ImportController/")).count()).isEqualTo(17);
        Map<String, String> serviceOf = Map.of("Title", "catalog-service", "Author", "catalog-service",
                "Publisher", "catalog-service", "Category", "catalog-service", "ShelfLocation", "catalog-service",
                "User", "identity-service", "StockItem", "sales-service", "Supplier", "sales-service");
        Map<String, String> typeOf = Map.of("Title", "titles", "Author", "authors", "Publisher", "publishers",
                "Category", "categories", "ShelfLocation", "shelf-locations", "User", "users", "StockItem",
                "stock-items", "Supplier", "suppliers");
        Map<String, String> moduleOf = Map.of("catalog-service", "catalog", "identity-service", "identity",
                "sales-service", "sales");
        serviceOf.forEach((name, service) -> {
            String base = "/api/" + moduleOf.get(service) + "/imports/" + typeOf.get(name);
            assertThat(p.get(name + "ImportController/upload")).containsExactly(service, "POST", base);
            assertThat(p.get(name + "ImportController/confirm")).containsExactly(service, "POST",
                    base + "/{importId}/confirm");
        });
        assertThat(p.get("UserImportController/credentials")).containsExactly("identity-service", "GET",
                "/api/identity/imports/users/{importId}/credentials");
        assertThat(p.get("TitleController/lookupByIsbn")).containsExactly("catalog-service", "POST",
                "/api/books/lookup-by-isbn");
    }

    @Test
    void roleTemplateMatrixFollowsTheAdr() throws Exception {
        Map<String, Set<String>> g = grants(sql());
        Set<String> catalogFour = pair("Title", "Author", "Publisher", "Category");
        Set<String> salesTwo = pair("StockItem", "Supplier");

        Set<String> admin = new TreeSet<>(catalogFour);
        admin.addAll(pair("ShelfLocation"));
        admin.addAll(List.of("UserImportController/upload", "UserImportController/confirm", "UserImportController/credentials"));
        assertThat(g.get(LIBRARY_ADMIN)).isEqualTo(admin);

        Set<String> librarian = new TreeSet<>(catalogFour);
        librarian.addAll(pair("ShelfLocation"));
        assertThat(g.get(LIBRARIAN)).isEqualTo(librarian);

        Set<String> stock = new TreeSet<>(salesTwo);
        stock.add("TitleController/lookupByIsbn");
        assertThat(g.get(STOCK_KEEPER)).isEqualTo(stock);

        Set<String> store = new TreeSet<>(catalogFour);
        store.addAll(salesTwo);
        store.add("TitleController/lookupByIsbn");
        assertThat(g.get(STORE_MANAGER)).isEqualTo(store);
    }

    @Test
    void everyGrantedKeyIsDeclaredAndUploadConfirmComeTogether() throws Exception {
        String sql = sql();
        Set<String> declared = permissions(sql).keySet();
        for (Set<String> keys : grants(sql).values()) {
            assertThat(declared).containsAll(keys);
            for (String k : keys) {
                if (k.endsWith("ImportController/upload")) assertThat(keys).contains(k.replace("/upload", "/confirm"));
                if (k.endsWith("ImportController/confirm")) assertThat(keys).contains(k.replace("/confirm", "/upload"));
            }
        }
        // Không backfill role của tenant đang chạy: seed chỉ chạm bảng toàn cục
        assertThat(sql).doesNotContain("INSERT INTO role_permissions").doesNotContain("INSERT INTO roles");
    }

    private static Set<String> pair(String... names) {
        Set<String> out = new TreeSet<>();
        List<String> list = new ArrayList<>(List.of(names));
        for (String n : list) {
            out.add(n + "ImportController/upload");
            out.add(n + "ImportController/confirm");
        }
        return out;
    }
}
