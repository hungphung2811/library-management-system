package lms.tenant.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** Hợp đồng ZIP của ADR-0031 Phụ lục A: BOM, escape CSV, _meta.json cuối, bảng rỗng, chia file, lỗi giữa chừng. */
class TenantDataExporterTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Đọc giả lập: trả các dòng cố định, ghi lại tenant mà TenantContext đang bind. */
    private static final class FakeReader implements TenantTableReader {
        final Map<String, List<List<String>>> data = new LinkedHashMap<>();
        final List<UUID> seenTenants = new ArrayList<>();
        final List<Integer> batches = new ArrayList<>();
        String failOn;

        @Override
        public long read(ExportTable table, UUID tenantId, int batchSize, RowConsumer sink) throws IOException {
            seenTenants.add(TenantContext.current().orElse(null));
            batches.add(batchSize);
            if (table.name().equals(failOn)) throw new IOException("lỗi giả lập");
            List<List<String>> rows = data.getOrDefault(table.name(), List.of());
            for (List<String> row : rows) sink.accept(row);
            return rows.size();
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip, List<String> order) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
                order.add(e.getName());
            }
        }
        return out;
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private TenantDataExporter exporter(FakeReader reader, ExportTable... tables) {
        return new TenantDataExporter("catalog", List.of(tables),
                List.of(new ExportExclusion("secrets", "bảng bí mật")), reader,
                TransactionOperations.withoutTransaction(), 3);
    }

    @Test
    void moiBangMotFileCsvCoBomVaMetaJsonLaEntryCuoi() throws Exception {
        FakeReader reader = new FakeReader();
        reader.data.put("authors", List.of(Arrays.asList("1", "Nguyễn Nhật Ánh"), Arrays.asList("2", null)));
        ExportTable authors = ExportTable.of("authors", List.of("id"), "id", "name");
        ExportTable empty = ExportTable.of("series", List.of("id"), "id", "name");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        exporter(reader, authors, empty).export(TENANT, out);

        List<String> order = new ArrayList<>();
        Map<String, byte[]> files = unzip(out.toByteArray(), order);
        assertThat(order).containsExactly("authors.csv", "series.csv", "_meta.json");
        byte[] csv = files.get("authors.csv");
        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        assertThat(text(csv)).isEqualTo("﻿id,name\r\n1,Nguyễn Nhật Ánh\r\n2,\r\n");
        assertThat(text(files.get("series.csv"))).isEqualTo("﻿id,name\r\n");
        byte[] meta = files.get("_meta.json");
        assertThat(meta[0]).isNotEqualTo((byte) 0xEF);
        assertThat(text(meta)).isEqualTo("{\"service\":\"catalog\",\"schemaVersion\":1,\"tables\":["
                + "{\"name\":\"authors\",\"file\":\"authors.csv\",\"rows\":2,\"columns\":[\"id\",\"name\"]},"
                + "{\"name\":\"series\",\"file\":\"series.csv\",\"rows\":0,\"columns\":[\"id\",\"name\"]}],"
                + "\"excluded\":[{\"table\":\"secrets\",\"reason\":\"bảng bí mật\"}]}");
    }

    @Test
    void escapeCsvDauPhayNhayKepXuongDongVaTiengViet() throws Exception {
        FakeReader reader = new FakeReader();
        reader.data.put("t", List.of(
                Arrays.asList("a,b", "say \"hi\"", "dòng 1\ndòng 2"),
                Arrays.asList("=SUM(A1)", "+84 90", "tiếng Việt: Đặng Thị Ngọc Ánh")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        exporter(reader, ExportTable.of("t", List.of("a"), "a", "b", "c")).export(TENANT, out);

        String csv = text(unzip(out.toByteArray(), new ArrayList<>()).get("t.csv"));
        assertThat(csv).isEqualTo("﻿a,b,c\r\n"
                + "\"a,b\",\"say \"\"hi\"\"\",\"dòng 1\ndòng 2\"\r\n"
                + "=SUM(A1),+84 90,tiếng Việt: Đặng Thị Ngọc Ánh\r\n");
    }

    @Test
    void tenantContextDuocBindChoTungBangVaKhoiPhucSauKhiXong() throws Exception {
        FakeReader reader = new FakeReader();
        TenantContext.set(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"));

        exporter(reader, ExportTable.of("a", List.of("id"), "id"), ExportTable.of("b", List.of("id"), "id"))
                .export(TENANT, new ByteArrayOutputStream());

        assertThat(reader.seenTenants).containsExactly(TENANT, TENANT);
        assertThat(reader.batches).containsOnly(3);
        assertThat(TenantContext.current()).contains(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"));
    }

    @Test
    void biaFileLonThanhNhieuPhanMoiPhanCoTieuDeVaMetaRieng() throws Exception {
        FakeReader reader = new FakeReader();
        reader.data.put("audit_logs", List.of(List.of("1"), List.of("2"), List.of("3"), List.of("4"), List.of("5")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        exporter(reader, ExportTable.of("audit_logs", List.of("id"), "id").splitEvery(2)).export(TENANT, out);

        List<String> order = new ArrayList<>();
        Map<String, byte[]> files = unzip(out.toByteArray(), order);
        assertThat(order).containsExactly("audit_logs_001.csv", "audit_logs_002.csv", "audit_logs_003.csv",
                "_meta.json");
        assertThat(text(files.get("audit_logs_001.csv"))).isEqualTo("﻿id\r\n1\r\n2\r\n");
        assertThat(text(files.get("audit_logs_003.csv"))).isEqualTo("﻿id\r\n5\r\n");
        assertThat(text(files.get("_meta.json"))).contains("\"name\":\"audit_logs_002\"")
                .contains("\"rows\":2").contains("\"rows\":1");
    }

    @Test
    void bangChiaFileRongVanCoPhanMotChiTieuDe() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter(new FakeReader(), ExportTable.of("audit_logs", List.of("id"), "id").splitEvery(2)).export(TENANT, out);
        Map<String, byte[]> files = unzip(out.toByteArray(), new ArrayList<>());
        assertThat(text(files.get("audit_logs_001.csv"))).isEqualTo("﻿id\r\n");
    }

    @Test
    void loiGiuaChungKhongGhiMetaJson() throws Exception {
        FakeReader reader = new FakeReader();
        reader.data.put("a", List.of(List.of("1")));
        reader.failOn = "b";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> exporter(reader, ExportTable.of("a", List.of("id"), "id"),
                ExportTable.of("b", List.of("id"), "id")).export(TENANT, out))
                .isInstanceOf(IOException.class).hasMessage("lỗi giả lập");

        assertThat(text(out.toByteArray())).doesNotContain("_meta.json");
    }

    @Test
    void metaKemCotBiLoaiCoChuDich() throws Exception {
        ExportTable t = ExportTable.of("notification_deliveries", List.of("id"), "id")
                .excluding("recipient_address", "địa chỉ đầy đủ");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter(new FakeReader(), t).export(TENANT, out);
        String meta = text(unzip(out.toByteArray(), new ArrayList<>()).get("_meta.json"));
        assertThat(meta).contains("{\"table\":\"notification_deliveries.recipient_address\","
                + "\"reason\":\"địa chỉ đầy đủ\"}");
    }

    @Test
    void khongChoXuatKhongCoTenant() {
        assertThatThrownBy(() -> exporter(new FakeReader()).export(null, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
