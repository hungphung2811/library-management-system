package lms.imports.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Dựng file .xlsx cho test (dùng POI DOM ở phía test được phép; mã chính thì không) và các file độc hại bằng tay. */
public final class XlsxFixtures {

    private XlsxFixtures() {
    }

    /** Sheet với dòng 1 là tiêu đề; ô là String, Number (double), java.time.LocalDate hoặc null. */
    public static void sheet(Workbook wb, String name, List<String> header, List<Object[]> rows) {
        Sheet s = wb.createSheet(name);
        Row h = s.createRow(0);
        for (int i = 0; i < header.size(); i++) h.createCell(i).setCellValue(header.get(i));
        int r = 1;
        for (Object[] data : rows) {
            Row row = s.createRow(r++);
            for (int c = 0; c < data.length; c++) {
                Object v = data[c];
                if (v == null) continue;
                if (v instanceof Number n) {
                    row.createCell(c).setCellValue(n.doubleValue());
                } else if (v instanceof java.time.LocalDate d) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    cell.setCellValue(d);
                    CellStyle st = wb.createCellStyle();
                    st.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
                    cell.setCellStyle(st);
                } else {
                    row.createCell(c).setCellValue(String.valueOf(v));
                }
            }
        }
    }

    public static void meta(Workbook wb, String type) {
        Sheet s = wb.createSheet("_meta");
        s.createRow(0).createCell(0).setCellValue("type");
        s.getRow(0).createCell(1).setCellValue(type);
    }

    public static byte[] bytes(Workbook wb) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            wb.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Workbook một sheet {@code DauSach} theo {@link SampleSchema}. */
    public static byte[] sample(List<Object[]> rows) {
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "DauSach", SampleSchema.HEADER, rows);
        meta(wb, "sample");
        return bytes(wb);
    }

    public static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Khung xlsx tối thiểu hợp lệ về cấu trúc (đủ để qua kiểm tra [Content_Types] và workbook). */
    public static Map<String, byte[]> skeleton() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("[Content_Types].xml", ("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>").getBytes());
        m.put("xl/workbook.xml", "<?xml version=\"1.0\"?><workbook/>".getBytes());
        return m;
    }

    public static Map<String, byte[]> entries(byte[] xlsx) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) m.put(e.getName(), zin.readAllBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return m;
    }

    public static byte[] rewrite(byte[] xlsx, String entry, UnaryOperator<byte[]> change) {
        Map<String, byte[]> m = entries(xlsx);
        if (!m.containsKey(entry)) throw new IllegalArgumentException("Không có entry " + entry);
        m.put(entry, change.apply(m.get(entry)));
        return zip(m);
    }
}
