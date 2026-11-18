package lms.catalog.catalog.unit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lms.imports.core.ImportSchema;
import lms.imports.core.SheetSpec;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Dựng file .xlsx cho test nhập catalog (POI DOM chỉ ở phía test; mã chính chỉ dùng API streaming). */
public final class CatalogImportRows {

    private CatalogImportRows() {
    }

    /** Dòng dữ liệu theo khóa cột (giá trị String, Number hoặc null). */
    public static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    public static byte[] xlsx(ImportSchema schema, Map<String, List<Map<String, Object>>> data) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            for (SheetSpec spec : schema.sheets()) {
                if (!data.containsKey(spec.name())) continue;
                Sheet s = wb.createSheet(spec.name());
                Row header = s.createRow(0);
                for (int c = 0; c < spec.columns().size(); c++) {
                    header.createCell(c).setCellValue(spec.columns().get(c).label());
                }
                int r = 1;
                for (Map<String, Object> values : data.get(spec.name())) {
                    Row row = s.createRow(r++);
                    for (int c = 0; c < spec.columns().size(); c++) {
                        Object v = values.get(spec.columns().get(c).key());
                        if (v == null) continue;
                        if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                        else row.createCell(c).setCellValue(String.valueOf(v));
                    }
                }
            }
            Sheet meta = wb.createSheet("_meta");
            meta.createRow(0).createCell(0).setCellValue("type");
            meta.getRow(0).createCell(1).setCellValue(schema.type());
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static byte[] single(ImportSchema schema, List<Map<String, Object>> rows) {
        return xlsx(schema, Map.of(schema.sheets().get(0).name(), rows));
    }
}
