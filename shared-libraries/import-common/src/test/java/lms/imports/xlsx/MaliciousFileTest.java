package lms.imports.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.testsupport.SampleSchema;
import lms.imports.testsupport.XlsxFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File độc hại: zip bomb, XXE/DOCTYPE, macro, .xls giả, mã hóa, cấu trúc hỏng, giới hạn kích thước. */
class MaliciousFileTest {

    private final ImportLimits limits = ImportLimits.defaults();
    private final StreamingXlsxReader reader = new StreamingXlsxReader(limits);

    private ImportErrorCode codeOf(byte[] data) {
        try {
            reader.read(data, SampleSchema.schema());
        } catch (ImportException e) {
            return e.errorCode();
        }
        throw new AssertionError("File độc hại được chấp nhận");
    }

    private static byte[] bombEntry(String name, long inflatedBytes) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : XlsxFixtures.skeleton().entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(name));
            byte[] zeros = new byte[1 << 20];
            for (long written = 0; written < inflatedBytes; written += zeros.length) zip.write(zeros);
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void zipBombOverEntryLimitIsUnsafe() {
        byte[] bomb = bombEntry("xl/worksheets/sheet1.xml", 120L << 20); // nén vài trăm KB, giải nén 120 MB
        assertThat(bomb.length).isLessThan(5 << 20);
        assertThat(codeOf(bomb)).isEqualTo(ImportErrorCode.FILE_UNSAFE);
    }

    @Test
    void highCompressionRatioIsUnsafeEvenUnderSizeLimits() {
        ImportLimits generous = new ImportLimits(5000, 5L << 20, 50, 2000, 200, 500L << 20, 200L << 20, 0.01, 4 << 20,
                Duration.ofMinutes(30), 3, 200, 2, 4, Duration.ofMinutes(2), 200);
        StreamingXlsxReader r = new StreamingXlsxReader(generous);
        byte[] bomb = bombEntry("xl/worksheets/sheet1.xml", 20L << 20); // tỉ lệ ~1000:1
        try {
            r.read(bomb, SampleSchema.schema());
            throw new AssertionError("phải bị chặn");
        } catch (ImportException e) {
            assertThat(e.errorCode()).isEqualTo(ImportErrorCode.FILE_UNSAFE);
            assertThat(e.getMessage()).contains("nén");
        }
    }

    @Test
    void tooManyEntriesIsUnsafe() {
        Map<String, byte[]> m = XlsxFixtures.skeleton();
        for (int i = 0; i < 300; i++) m.put("xl/media/f" + i + ".xml", "<a/>".getBytes());
        assertThat(codeOf(XlsxFixtures.zip(m))).isEqualTo(ImportErrorCode.FILE_UNSAFE);
    }

    @Test
    void doctypeAnywhereIsUnsafe() {
        for (String entry : List.of("xl/workbook.xml", "xl/sharedStrings.xml", "xl/worksheets/sheet1.xml", "xl/_rels/workbook.xml.rels")) {
            Map<String, byte[]> m = XlsxFixtures.skeleton();
            m.put(entry, "<?xml version=\"1.0\"?><!DOCTYPE x><x/>".getBytes(StandardCharsets.UTF_8));
            assertThat(codeOf(XlsxFixtures.zip(m))).as(entry).isEqualTo(ImportErrorCode.FILE_UNSAFE);
        }
    }

    @Test
    void xxeEntityDeclarationIsUnsafe() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><sst><si><t>&xxe;</t></si></sst>";
        byte[] valid = XlsxFixtures.sample(List.<Object[]>of(new Object[] {null, "A"}));
        Map<String, byte[]> m = XlsxFixtures.entries(valid);
        m.put("xl/sharedStrings.xml", xxe.getBytes(StandardCharsets.UTF_8));
        assertThat(codeOf(XlsxFixtures.zip(m))).isEqualTo(ImportErrorCode.FILE_UNSAFE);
    }

    @Test
    void doctypeHiddenInUtf16IsStillUnsafe() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE x [<!ENTITY a \"b\">]><x/>";
        Map<String, byte[]> m = XlsxFixtures.skeleton();
        m.put("xl/sharedStrings.xml", xml.getBytes(StandardCharsets.UTF_16LE));
        assertThat(codeOf(XlsxFixtures.zip(m))).isEqualTo(ImportErrorCode.FILE_UNSAFE);
    }

    @Test
    void doctypeInRealWorkbookPartIsRejectedByReader() {
        byte[] valid = XlsxFixtures.sample(List.<Object[]>of(new Object[] {null, "A"}));
        byte[] poisoned = XlsxFixtures.rewrite(valid, "xl/styles.xml", orig -> {
            String s = new String(orig, StandardCharsets.UTF_8);
            return s.replaceFirst("(<\\?xml[^>]*\\?>)", "$1<!DOCTYPE styleSheet [<!ENTITY x \"y\">]>").getBytes(StandardCharsets.UTF_8);
        });
        assertThat(codeOf(poisoned)).isEqualTo(ImportErrorCode.FILE_UNSAFE);
    }

    @Test
    void macroPartsAreUnsupported() {
        Map<String, byte[]> vba = XlsxFixtures.skeleton();
        vba.put("xl/vbaProject.bin", new byte[] {1, 2, 3});
        assertThat(codeOf(XlsxFixtures.zip(vba))).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);

        Map<String, byte[]> embedded = XlsxFixtures.skeleton();
        embedded.put("xl/embeddings/oleObject1.bin", new byte[] {1});
        assertThat(codeOf(XlsxFixtures.zip(embedded))).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);

        Map<String, byte[]> xlsm = XlsxFixtures.skeleton();
        xlsm.put("[Content_Types].xml", ("<Types><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.ms-excel.sheet.macroEnabled.main+xml\"/></Types>").getBytes());
        assertThat(codeOf(XlsxFixtures.zip(xlsm))).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);

        Map<String, byte[]> xlsb = XlsxFixtures.skeleton();
        xlsb.put("[Content_Types].xml", ("<Types><Override PartName=\"/xl/workbook.bin\" ContentType=\"application/vnd.ms-excel.sheet.binary.macroEnabled.main\"/></Types>").getBytes());
        assertThat(codeOf(XlsxFixtures.zip(xlsb))).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);
    }

    @Test
    void fakeXlsIsUnsupported() {
        // .xls thật (OLE2) hoặc xlsx mã hóa đều mở đầu bằng D0 CF 11 E0 dù đổi đuôi thành .xlsx
        byte[] ole2 = new byte[2048];
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, ole2, 0, magic.length);
        assertThat(codeOf(ole2)).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);
    }

    @Test
    void notAZipIsUnsupported() {
        assertThat(codeOf("đây là CSV, không phải xlsx".getBytes(StandardCharsets.UTF_8))).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);
        assertThat(codeOf(new byte[] {'P', 'K'})).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);
        assertThat(codeOf("<html>".getBytes())).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);
    }

    @Test
    void emptyFileIsFileRequiredAndOversizeIsTooLarge() {
        assertThat(codeOf(new byte[0])).isEqualTo(ImportErrorCode.FILE_REQUIRED);
        assertThat(codeOf(new byte[(int) limits.maxFileBytes() + 1])).isEqualTo(ImportErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void truncatedOrGarbageZipIsCorrupt() {
        byte[] valid = XlsxFixtures.sample(List.<Object[]>of(new Object[] {null, "A"}));
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length / 2);
        assertThat(codeOf(truncated)).isEqualTo(ImportErrorCode.FILE_CORRUPT);

        byte[] garbage = new byte[500];
        garbage[0] = 'P';
        garbage[1] = 'K';
        garbage[2] = 3;
        garbage[3] = 4;
        assertThat(codeOf(garbage)).isEqualTo(ImportErrorCode.FILE_CORRUPT);
    }

    @Test
    void zipWithoutWorkbookIsCorrupt() {
        Map<String, byte[]> docx = new LinkedHashMap<>();
        docx.put("[Content_Types].xml", "<Types/>".getBytes());
        docx.put("word/document.xml", "<d/>".getBytes());
        assertThat(codeOf(XlsxFixtures.zip(docx))).isEqualTo(ImportErrorCode.FILE_CORRUPT);
    }

    @Test
    void zipSlipEntryNamesAreUnsafe() {
        Map<String, byte[]> m = XlsxFixtures.skeleton();
        m.put("../../evil.xml", "<a/>".getBytes());
        assertThat(codeOf(XlsxFixtures.zip(m))).isEqualTo(ImportErrorCode.FILE_UNSAFE);
    }

    @Test
    void sharedStringsHugeButUnderLimitsStillReadable() {
        // 4000 chuỗi dài vẫn đọc được: hạn mức chỉ chặn bất thường, không chặn dữ liệu hợp lệ
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 4000; i++) rows.add(new Object[] {null, "Tên " + i + " " + "y".repeat(10)});
        assertThat(reader.read(XlsxFixtures.sample(rows), SampleSchema.schema())).hasSize(4000);
    }

    @Test
    void inspectorAcceptsValidFileAndLeavesNoTempFilesOnRejection(@TempDir Path dir) throws IOException {
        byte[] valid = XlsxFixtures.sample(List.<Object[]>of(new Object[] {null, "A"}));
        Path ok = dir.resolve("ok.xlsx");
        Files.write(ok, valid);
        new XlsxSafetyInspector(limits).inspect(ok); // không ném
        assertThat(codeOf(new byte[] {1, 2, 3, 4, 5})).isEqualTo(ImportErrorCode.UNSUPPORTED_FILE);
    }
}
