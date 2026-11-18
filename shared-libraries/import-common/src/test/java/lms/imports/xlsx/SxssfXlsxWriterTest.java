package lms.imports.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.IssuedCredential;
import lms.imports.core.RowIssue;
import lms.imports.core.RowStatus;
import lms.imports.testsupport.SampleSchema;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SxssfXlsxWriterTest {

    private final SxssfXlsxWriter writer = new SxssfXlsxWriter(ImportLimits.defaults());
    private final StreamingXlsxReader reader = new StreamingXlsxReader(ImportLimits.defaults());

    private static XSSFWorkbook open(byte[] data) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(data));
    }

    @Test
    void templateHasGuideDataSheetsHiddenMetaTextFormatAndDropdowns() throws IOException {
        byte[] file = writer.template(SampleSchema.schema());

        try (XSSFWorkbook wb = open(file)) {
            assertThat(wb.getSheetName(0)).isEqualTo("HuongDan");
            assertThat(wb.getSheet("DauSach")).isNotNull();
            assertThat(wb.getSheet("BanSao")).isNotNull();
            assertThat(wb.getSheetVisibility(wb.getSheetIndex("_meta"))).isEqualTo(SheetVisibility.HIDDEN);
            XSSFSheet meta = wb.getSheet("_meta");
            assertThat(meta.getRow(0).getCell(1).getStringCellValue()).isEqualTo("sample");

            XSSFSheet data = wb.getSheet("DauSach");
            assertThat(data.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Tên sách *"); // cột bắt buộc có *
            assertThat(data.getRow(0).getCell(0).getStringCellValue()).isEqualTo("ISBN");
            // cột ISBN (văn bản) có định dạng @ để Excel không đổi số thành mũ
            XSSFCellStyle isbnStyle = (XSSFCellStyle) data.getColumnStyle(0);
            assertThat(isbnStyle.getDataFormatString()).isEqualTo("@");
            // cột enum có dropdown
            assertThat(data.getDataValidations()).hasSize(1);
            assertThat(data.getDataValidations().get(0).getValidationConstraint().getExplicitListValues())
                    .containsExactly("PAPERBACK", "HARDCOVER");
            // không có công thức, không có hyperlink
            for (Sheet s : wb) {
                for (org.apache.poi.ss.usermodel.Row row : s) {
                    for (Cell c : row) assertThat(c.getCellType()).isNotEqualTo(CellType.FORMULA);
                }
                assertThat(((XSSFSheet) s).getHyperlinkList()).isEmpty();
            }
        }
    }

    @Test
    void templateIsReadableAsAnUploadWithNoDataRows() {
        // mẫu rỗng đúng cấu trúc: reader nhận ra sheet/cột/_meta, chỉ báo thiếu dữ liệu
        try {
            reader.read(writer.template(SampleSchema.schema()), SampleSchema.schema());
            throw new AssertionError("mẫu rỗng phải báo EMPTY");
        } catch (lms.imports.core.ImportException e) {
            assertThat(e.errorCode()).isEqualTo(lms.imports.core.ImportErrorCode.EMPTY);
        }
    }

    @Test
    void errorFileKeepsValuesAddsStatusAndErrorAndRoundTripsFormulaLookingText() throws IOException {
        String formula = "=HYPERLINK(\"http://evil\",\"x\")";
        String cmd = "-2+3";
        ImportRow bad = new ImportRow("DauSach", 7, Map.of("name", formula, "isbn", cmd, "price", "@SUM(A1)"),
                RowStatus.INVALID, List.of(RowIssue.error("name", "BAD", "Sai quy tắc")), Map.of());
        ImportRow dup = new ImportRow("DauSach", 9, Map.of("name", "Sách trùng"), RowStatus.DUPLICATE,
                List.of(RowIssue.error("name", "ALREADY_EXISTS", "Đã tồn tại")), Map.of());

        byte[] file = writer.errorFile(SampleSchema.schema(), List.of(bad, dup));

        try (XSSFWorkbook wb = open(file)) {
            XSSFSheet s = wb.getSheet("DauSach");
            assertThat(s.getRow(0).getCell(7).getStringCellValue()).isEqualTo("Trạng thái");
            assertThat(s.getRow(0).getCell(8).getStringCellValue()).isEqualTo("Lỗi");
            Cell name = s.getRow(1).getCell(1);
            assertThat(name.getCellType()).isEqualTo(CellType.STRING);            // không bao giờ là công thức
            assertThat(name.getStringCellValue()).isEqualTo(formula);              // nội dung nguyên vẹn, không chèn dấu '
            assertThat(((XSSFCellStyle) name.getCellStyle()).getQuotePrefixed()).isTrue();
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo(cmd);
            assertThat(((XSSFCellStyle) s.getRow(1).getCell(0).getCellStyle()).getQuotePrefixed()).isTrue();
            assertThat(s.getRow(1).getCell(2).getStringCellValue()).isEqualTo("@SUM(A1)");
            // ô thường không bị quotePrefixed
            Cell dupName = s.getRow(2).getCell(1);
            assertThat(((XSSFCellStyle) dupName.getCellStyle()).getQuotePrefixed()).isFalse();
            assertThat(s.getRow(1).getCell(7).getStringCellValue()).isEqualTo("Lỗi");
            assertThat(s.getRow(1).getCell(8).getStringCellValue()).contains("Tên sách").contains("Sai quy tắc");
            assertThat(s.getRow(2).getCell(7).getStringCellValue()).isEqualTo("Trùng");
            for (Cell c : s.getRow(1)) assertThat(c.getCellType()).isNotEqualTo(CellType.FORMULA);
        }

        // upload lại được: cột Trạng thái/Lỗi bị bỏ qua, giá trị đi vòng đúng như gốc
        List<ImportRow> again = reader.read(file, SampleSchema.schema());
        assertThat(again).hasSize(2);
        assertThat(again.get(0).value("name")).isEqualTo(formula);
        assertThat(again.get(0).value("isbn")).isEqualTo(cmd);
        assertThat(again.get(0).value("price")).isEqualTo("@SUM(A1)");
        assertThat(again.get(0).values()).doesNotContainKeys("Trạng thái", "Lỗi");
    }

    @Test
    void errorFileForEmptyListStillHasAllSheetsAndMeta() throws IOException {
        try (XSSFWorkbook wb = open(writer.errorFile(SampleSchema.schema(), List.of()))) {
            assertThat(wb.getSheet("DauSach").getLastRowNum()).isZero();
            assertThat(wb.getSheet("BanSao")).isNotNull();
            assertThat(wb.getSheet("_meta")).isNotNull();
        }
    }

    @Test
    void credentialsFileStoresSecretsAsPlainStrings() throws IOException {
        byte[] file = writer.credentials(List.of(new IssuedCredential("an", "-Xy9=abc"), new IssuedCredential("binh", "Zz@1")));
        try (XSSFWorkbook wb = open(file)) {
            XSSFSheet s = wb.getSheetAt(0);
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("an");
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).isEqualTo("-Xy9=abc");
            assertThat(s.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(((XSSFCellStyle) s.getRow(1).getCell(1).getCellStyle()).getQuotePrefixed()).isTrue();
        }
    }

    @Test
    void largeErrorFileIsStreamedWithoutProblems() throws IOException {
        java.util.List<ImportRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            rows.add(new ImportRow("DauSach", i + 2, Map.of("name", "Tên " + i), RowStatus.INVALID,
                    List.of(RowIssue.error("name", "X", "Lỗi " + i)), Map.of()));
        }
        byte[] file = writer.errorFile(SampleSchema.schema(), rows);
        try (XSSFWorkbook wb = open(file)) {
            assertThat(wb.getSheet("DauSach").getLastRowNum()).isEqualTo(5000);
        }
    }
}
