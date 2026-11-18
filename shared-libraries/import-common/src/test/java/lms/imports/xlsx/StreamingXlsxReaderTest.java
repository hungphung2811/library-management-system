package lms.imports.xlsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.RowStatus;
import lms.imports.testsupport.SampleSchema;
import lms.imports.testsupport.XlsxFixtures;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class StreamingXlsxReaderTest {

    private final StreamingXlsxReader reader = new StreamingXlsxReader(ImportLimits.defaults());

    private ImportException read(byte[] data) {
        return org.junit.jupiter.api.Assertions.assertThrows(ImportException.class, () -> reader.read(data, SampleSchema.schema()));
    }

    @Test
    void parsesValidFileWithTypesNormalizedToStrings() {
        byte[] file = XlsxFixtures.sample(List.of(
                new Object[] {9786041234567.0, "Sách A", 120000.5, LocalDate.of(2024, 3, 9), "PAPERBACK", true, "a;b"},
                new Object[] {"0123456789", "Sách B", 50, null, null, null, null}));

        List<ImportRow> rows = reader.read(file, SampleSchema.schema());

        assertThat(rows).hasSize(2);
        ImportRow first = rows.get(0);
        assertThat(first.sheet()).isEqualTo("DauSach");
        assertThat(first.rowNumber()).isEqualTo(2);
        assertThat(first.value("isbn")).isEqualTo("9786041234567"); // số lớn không thành 9.78604E+12
        assertThat(first.value("name")).isEqualTo("Sách A");
        assertThat(first.value("price")).isEqualTo("120000.5");
        assertThat(first.value("published")).isEqualTo("2024-03-09"); // ngày Excel thành yyyy-MM-dd
        assertThat(first.value("active")).isEqualTo("true");
        assertThat(first.status()).isEqualTo(RowStatus.VALID);
        assertThat(rows.get(1).value("isbn")).isEqualTo("0123456789"); // giữ số 0 đầu của chuỗi
        assertThat(rows.get(1).value("price")).isEqualTo("50"); // không ".0"
        assertThat(rows.get(1).rowNumber()).isEqualTo(3);
    }

    @Test
    void skipsBlankRowsAndKeepsExcelRowNumbers() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("DauSach");
        s.createRow(0).createCell(1).setCellValue("Tên sách");
        s.createRow(2).createCell(1).setCellValue("Sau dòng trống");
        s.createRow(3).createCell(1).setCellValue("   ");
        byte[] file = XlsxFixtures.bytes(wb);

        List<ImportRow> rows = reader.read(file, SampleSchema.schema());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rowNumber()).isEqualTo(3);
        assertThat(rows.get(0).value("name")).isEqualTo("Sau dòng trống");
    }

    @Test
    void matchesHeadersByKeyAndIgnoresUnknownColumns() {
        XSSFWorkbook wb = new XSSFWorkbook();
        XlsxFixtures.sheet(wb, "dausach", List.of("name", "Cột lạ", "Trạng thái", "Lỗi"),
                List.<Object[]>of(new Object[] {"Tên X", "bỏ", "Lỗi", "x"}));
        byte[] file = XlsxFixtures.bytes(wb);

        List<ImportRow> rows = reader.read(file, SampleSchema.schema());

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.value("name")).isEqualTo("Tên X");
            assertThat(r.values()).doesNotContainKey("Cột lạ");
        });
    }

    @Test
    void missingRequiredColumnIsTemplateMismatchListingTheColumn() {
        XSSFWorkbook wb = new XSSFWorkbook();
        XlsxFixtures.sheet(wb, "DauSach", List.of("ISBN"), List.<Object[]>of(new Object[] {"1"}));
        ImportException e = read(XlsxFixtures.bytes(wb));

        assertThat(e.errorCode()).isEqualTo(ImportErrorCode.TEMPLATE_MISMATCH);
        assertThat(e.details()).anyMatch(d -> d.contains("Tên sách"));
    }

    @Test
    void missingRequiredSheetIsTemplateMismatch() {
        XSSFWorkbook wb = new XSSFWorkbook();
        XlsxFixtures.sheet(wb, "BanSao", List.of("ISBN", "Mã vạch"), List.<Object[]>of(new Object[] {"1", "B1"}));
        ImportException e = read(XlsxFixtures.bytes(wb));

        assertThat(e.errorCode()).isEqualTo(ImportErrorCode.TEMPLATE_MISMATCH);
        assertThat(e.details()).anyMatch(d -> d.contains("DauSach"));
    }

    @Test
    void optionalSecondSheetIsReadWhenPresent() {
        XSSFWorkbook wb = new XSSFWorkbook();
        XlsxFixtures.sheet(wb, "DauSach", SampleSchema.HEADER, List.<Object[]>of(new Object[] {"1", "A"}));
        XlsxFixtures.sheet(wb, "BanSao", List.of("ISBN *", "Mã vạch *"), List.<Object[]>of(new Object[] {"1", "B1"}));
        List<ImportRow> rows = reader.read(XlsxFixtures.bytes(wb), SampleSchema.schema());

        assertThat(rows).extracting(ImportRow::sheet).containsExactly("DauSach", "BanSao");
    }

    @Test
    void metaSheetOfAnotherTypeIsRejected() {
        XSSFWorkbook wb = new XSSFWorkbook();
        XlsxFixtures.sheet(wb, "DauSach", SampleSchema.HEADER, List.<Object[]>of(new Object[] {"1", "A"}));
        XlsxFixtures.meta(wb, "users");

        assertThat(read(XlsxFixtures.bytes(wb)).errorCode()).isEqualTo(ImportErrorCode.TEMPLATE_MISMATCH);
    }

    @Test
    void headerOnlyFileIsEmpty() {
        assertThat(read(XlsxFixtures.sample(List.of())).errorCode()).isEqualTo(ImportErrorCode.EMPTY);
    }

    @Test
    void stopsAtRowLimit() {
        StreamingXlsxReader small = new StreamingXlsxReader(new ImportLimits(5, 5L << 20, 50, 2000, 200, 100L << 20, 50L << 20,
                0.01, 4 << 20, java.time.Duration.ofMinutes(30), 3, 200, 2, 4, java.time.Duration.ofMinutes(2), 200));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(new Object[] {null, "S" + i});
        byte[] file = XlsxFixtures.sample(rows);

        assertThatThrownBy(() -> small.read(file, SampleSchema.schema()))
                .isInstanceOfSatisfying(ImportException.class, e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.TOO_MANY_ROWS));
        // đúng 5 dòng thì được
        assertThat(small.read(XlsxFixtures.sample(rows.subList(0, 5)), SampleSchema.schema())).hasSize(5);
    }

    @Test
    void fiveThousandRowsAreAcceptedAndFiveThousandOneRejected() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) rows.add(new Object[] {null, "Sách " + i});
        assertThat(reader.read(XlsxFixtures.sample(rows), SampleSchema.schema())).hasSize(5000);
        rows.add(new Object[] {null, "thừa"});
        assertThat(read(XlsxFixtures.sample(rows)).errorCode()).isEqualTo(ImportErrorCode.TOO_MANY_ROWS);
    }

    @Test
    void cellOverTwoThousandCharsBecomesRowErrorNotFileError() {
        List<Object[]> rows = List.<Object[]>of(new Object[] {null, "x".repeat(10_000)}, new Object[] {null, "ok"});
        List<ImportRow> read = reader.read(XlsxFixtures.sample(rows), SampleSchema.schema());

        assertThat(read.get(0).status()).isEqualTo(RowStatus.INVALID);
        assertThat(read.get(0).issues()).anyMatch(i -> i.code().equals("CELL_TOO_LONG") && "name".equals(i.column()));
        assertThat(read.get(0).value("name")).hasSize(2000);
        assertThat(read.get(1).status()).isEqualTo(RowStatus.VALID);
    }

    @Test
    void formulaCellsAreNeverEvaluated() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("DauSach");
        Row h = s.createRow(0);
        h.createCell(0).setCellValue("Tên sách");
        h.createCell(1).setCellValue("ISBN");
        Row r = s.createRow(1);
        r.createCell(0).setCellFormula("1+1");              // không có giá trị lưu sẵn: coi là trống
        r.createCell(1).setCellFormula("HYPERLINK(\"http://evil\",\"x\")");
        Row r2 = s.createRow(2);
        r2.createCell(0).setCellValue("=cmd|' /C calc'!A0"); // chuỗi thường bắt đầu bằng "=": giữ nguyên là văn bản
        byte[] file = XlsxFixtures.bytes(wb);

        List<ImportRow> rows = reader.read(file, SampleSchema.schema());

        assertThat(rows).hasSize(1); // dòng toàn công thức không giá trị là dòng trống
        assertThat(rows.get(0).value("name")).isEqualTo("=cmd|' /C calc'!A0");
        assertThat(rows.get(0).rowNumber()).isEqualTo(3);
    }

    @Test
    void controlCharactersAreStrippedAndTextIsNfc() {
        String decomposed = "Café"; // e + dấu sắc rời
        byte[] file = XlsxFixtures.sample(List.<Object[]>of(new Object[] {null, "  " + decomposed + "  "}));

        assertThat(reader.read(file, SampleSchema.schema()).get(0).value("name")).isEqualTo("Café");
        // ký tự điều khiển bị bỏ (POI ghi chúng dạng _x0001_ trong XML nên kiểm trực tiếp ở bộ chuẩn hóa)
        assertThat(CellSanitizer.normalize("a\u0001b\u0007c\td\ne\r")).isEqualTo("abc\td\ne");
    }

    @Test
    void worksWhenSheetHeaderHasStarAndExtraSpaces() {
        XSSFWorkbook wb = new XSSFWorkbook();
        XlsxFixtures.sheet(wb, " DauSach ", List.of("  TÊN   SÁCH * "), List.<Object[]>of(new Object[] {"A"}));
        assertThat(reader.read(XlsxFixtures.bytes(wb), SampleSchema.schema())).hasSize(1);
    }

    @Test
    void unusedCellImport() {
        // đảm bảo import Cell dùng (tránh cảnh báo) và ô số nguyên lớn đọc đúng
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("DauSach");
        s.createRow(0).createCell(0).setCellValue("Tên sách");
        Cell c = s.createRow(1).createCell(0);
        c.setCellValue(1234567890123456.0);
        assertThat(reader.read(XlsxFixtures.bytes(wb), SampleSchema.schema()).get(0).value("name")).isEqualTo("1234567890123456");
    }
}
