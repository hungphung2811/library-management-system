package lms.sales.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lms.sales.tenantdata.application.ExportSalesTenantDataUseCase;
import lms.sales.tenantdata.application.TenantTableReader;
import lms.sales.tenantdata.domain.ExcludedItem;
import lms.sales.tenantdata.domain.ExportTable;
import org.junit.jupiter.api.Test;

/** Phân trang theo con trỏ, BOM, escape CSV, chống công thức, kiểu ô và cấu trúc ZIP/_meta.json (Phụ lục A ADR-0031). */
class ExportSalesTenantDataUseCaseTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    /** Bảng giả có đủ mọi kiểu cột; khóa là id (uuid). */
    private static final ExportTable ITEMS = ExportTable.table("items", "id", "id:uuid", "name:text", "qty:num",
            "active:bool", "at:ts", "day:date", "extra:json").exclude("secret", "bí mật").build();
    private static final ExportTable EMPTY = ExportTable.table("empty_table", "tenant_id", "tenant_id:uuid", "x:text")
            .build();

    /** Reader trong bộ nhớ, ghi nhận mọi lần gọi để kiểm số lô và con trỏ. */
    static final class FakeReader implements TenantTableReader {
        final Map<String, List<Object[]>> data = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public List<Object[]> readBatch(UUID tenantId, ExportTable table, Object afterKey, int limit) {
            assertThat(tenantId).isEqualTo(TENANT);
            calls.add(table.name() + ">" + afterKey);
            List<Object[]> all = data.getOrDefault(table.name(), List.of());
            List<Object[]> out = new ArrayList<>();
            for (Object[] row : all) {
                if (afterKey == null || row[table.keyIndex()].toString().compareTo(afterKey.toString()) > 0) {
                    out.add(row);
                    if (out.size() == limit) {
                        break;
                    }
                }
            }
            return out;
        }
    }

    private static List<Object[]> rows(Object[]... r) {
        return new ArrayList<>(java.util.Arrays.asList(r));
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    private static Object[] row(int n, String name) {
        return new Object[] {id(n), name, (long) n, n % 2 == 0, Instant.parse("2026-11-19T10:15:30.123456Z"),
                LocalDate.of(2026, 11, 19), "{\"a\":1}"};
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries.put(e.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private ExportSalesTenantDataUseCase useCase(FakeReader reader, int batch) {
        return new ExportSalesTenantDataUseCase(reader, List.of(ITEMS, EMPTY),
                List.of(new ExcludedItem("x.secret", "lý do \"đặc biệt\"")), "sales", 1, batch);
    }

    private Map<String, byte[]> run(FakeReader reader, int batch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        useCase(reader, batch).export(TENANT, out);
        return unzip(out.toByteArray());
    }

    @Test
    void zipCoMotCsvMoiBangVaMetaJsonLaEntryCuoi() throws IOException {
        FakeReader reader = new FakeReader();
        reader.data.put("items", rows(row(1, "a")));
        Map<String, byte[]> zip = run(reader, 10);
        assertThat(zip.keySet()).containsExactly("items.csv", "empty_table.csv", "_meta.json");
    }

    @Test
    void csvCoBomUtf8VaDongTieuDeVaBangRongVanCoFile() throws IOException {
        FakeReader reader = new FakeReader();
        Map<String, byte[]> zip = run(reader, 10);
        byte[] items = zip.get("items.csv");
        assertThat(items[0]).isEqualTo((byte) 0xEF);
        assertThat(items[1]).isEqualTo((byte) 0xBB);
        assertThat(items[2]).isEqualTo((byte) 0xBF);
        String text = new String(items, 3, items.length - 3, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("id,name,qty,active,at,day,extra\r\n");
        byte[] empty = zip.get("empty_table.csv");
        assertThat(new String(empty, 3, empty.length - 3, StandardCharsets.UTF_8)).isEqualTo("tenant_id,x\r\n");
    }

    @Test
    void kieuOGhiDungDinhDangHopDong() throws IOException {
        FakeReader reader = new FakeReader();
        reader.data.put("items", rows(row(2, "sách")));
        String text = csv(run(reader, 10).get("items.csv"));
        assertThat(text).contains("00000000-0000-0000-0000-000000000002,sách,2,true,2026-11-19T10:15:30.123456Z,"
                + "2026-11-19,\"{\"\"a\"\":1}\"\r\n");
    }

    @Test
    void nullLaOTrongVaSoAmGiuNguyen() throws IOException {
        FakeReader reader = new FakeReader();
        reader.data.put("items", rows(new Object[] {id(1), null, -5L, null, null, null, null}));
        String text = csv(run(reader, 10).get("items.csv"));
        assertThat(text).endsWith("00000000-0000-0000-0000-000000000001,,-5,,,,\r\n");
    }

    @Test
    void escapeDauPhayNhayKepXuongDongVaUnicodeTiengViet() throws IOException {
        FakeReader reader = new FakeReader();
        reader.data.put("items", rows(
                row(1, "a,b"), row(2, "nói \"xin chào\""), row(3, "dòng 1\ndòng 2"), row(4, "Nguyễn Văn Ánh – Đặng"),
                row(5, "cr\rlf")));
        String text = csv(run(reader, 10).get("items.csv"));
        assertThat(text).contains(",\"a,b\",").contains(",\"nói \"\"xin chào\"\"\",")
                .contains(",\"dòng 1\ndòng 2\",").contains(",Nguyễn Văn Ánh – Đặng,").contains(",\"cr\rlf\",");
    }

    @Test
    void congThucExcelNguyHiemBiVoHieuHoaOODangVanBanKhongPhaiOSo() throws IOException {
        FakeReader reader = new FakeReader();
        reader.data.put("items", rows(row(1, "=HYPERLINK(\"http://x\")"), row(2, "+84901234567"), row(3, "-1+2"),
                row(4, "@SUM(A1)"), row(5, "\tTab"), row(6, "\rCr")));
        String text = csv(run(reader, 10).get("items.csv"));
        assertThat(text).contains(",\"'=HYPERLINK(\"\"http://x\"\")\",").contains(",'+84901234567,")
                .contains(",'-1+2,").contains(",'@SUM(A1),").contains(",'\tTab,").contains(",\"'\rCr\",");
        // ô số không bị đụng tới
        assertThat(text).contains(",1,false,").doesNotContain(",'1,");
    }

    @Test
    void phanTrangTheoConTroKhongBoSotKhongTrungVaSoCauTheoSoLo() throws IOException {
        FakeReader reader = new FakeReader();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            rows.add(row(i, "n" + i));
        }
        reader.data.put("items", rows);
        Map<String, byte[]> zip = run(reader, 10);
        String text = csv(zip.get("items.csv"));
        String[] lines = text.split("\r\n");
        assertThat(lines).hasSize(26); // tiêu đề + 25 dòng
        for (int i = 1; i <= 25; i++) {
            assertThat(lines[i]).startsWith(id(i).toString());
        }
        // 3 lô (10, 10, 5) cho items, 1 lô cho bảng rỗng; con trỏ là khóa của dòng cuối lô trước
        assertThat(reader.calls).containsExactly("items>null", "items>" + id(10), "items>" + id(20),
                "empty_table>null");
        assertThat(new String(zip.get("_meta.json"), StandardCharsets.UTF_8)).contains("\"rows\":25");
    }

    @Test
    void loDayDungCanThemMotLoRongDeKetThuc() throws IOException {
        FakeReader reader = new FakeReader();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(row(i, "n" + i));
        }
        reader.data.put("items", rows);
        run(reader, 10);
        // lô thứ ba rỗng là cần thiết khi lô trước đầy đúng: kiểm hành vi cố định
        assertThat(reader.calls).containsExactly("items>null", "items>" + id(10), "items>" + id(20), "empty_table>null");
    }

    @Test
    void metaJsonDungCauTrucKhongBomVaCoCotVaBiLoai() throws IOException {
        FakeReader reader = new FakeReader();
        reader.data.put("items", rows(row(1, "a"), row(2, "b")));
        byte[] meta = run(reader, 10).get("_meta.json");
        assertThat(meta[0]).isNotEqualTo((byte) 0xEF);
        assertThat(new String(meta, StandardCharsets.UTF_8)).isEqualTo(
                "{\"service\":\"sales\",\"schemaVersion\":1,\"tables\":["
                        + "{\"name\":\"items\",\"file\":\"items.csv\",\"rows\":2,\"columns\":"
                        + "[\"id\",\"name\",\"qty\",\"active\",\"at\",\"day\",\"extra\"]},"
                        + "{\"name\":\"empty_table\",\"file\":\"empty_table.csv\",\"rows\":0,\"columns\":"
                        + "[\"tenant_id\",\"x\"]}],"
                        + "\"excluded\":[{\"table\":\"x.secret\",\"reason\":\"lý do \\\"đặc biệt\\\"\"}]}");
    }

    @Test
    void loiGiuaChungNemRaVaKhongGhiMeta() {
        TenantTableReader failing = (tenantId, table, afterKey, limit) -> {
            throw new IllegalStateException("db chết");
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> new ExportSalesTenantDataUseCase(failing, List.of(ITEMS), List.of(), "sales", 1, 10)
                .export(TENANT, out)).isInstanceOf(IllegalStateException.class);
        assertThat(new String(out.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("_meta.json");
    }

    @Test
    void cotBiLoaiKhongDuocDongThoiLaCotXuat() {
        assertThatThrownBy(() -> ExportTable.table("t", "id", "id:uuid", "secret:text").exclude("secret", "x").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportTable.table("t", "id", "id:uuid", "bad name:text").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportTable.table("t", "n", "id:uuid", "n:num").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String csv(byte[] bytes) {
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
}
