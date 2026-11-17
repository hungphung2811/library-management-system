package lms.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvWriterTest {

    private static String write(Consumer<CsvWriter> body) {
        StringWriter sw = new StringWriter();
        CsvWriter w = CsvWriter.toWriter(sw);
        body.accept(w);
        w.flush();
        return sw.toString();
    }

    @Test
    void ghiDongVoiCRLFVaPhanCachBangDauPhay() {
        String csv = write(w -> w.header("a", "b").text("x").text("y").endRow());
        assertThat(csv).isEqualTo("a,b\r\nx,y\r\n");
    }

    @Test
    void boOCoDauPhayVaNhanDoiNgoacKep() {
        assertThat(write(w -> w.text("a,b").text("nói \"xin chào\"").endRow()))
                .isEqualTo("\"a,b\",\"nói \"\"xin chào\"\"\"\r\n");
    }

    @Test
    void boOCoXuongDong() {
        assertThat(write(w -> w.text("dòng1\ndòng2").text("c\r\nd").endRow()))
                .isEqualTo("\"dòng1\ndòng2\",\"c\r\nd\"\r\n");
    }

    @Test
    void oNullVaRongLaORong() {
        assertThat(write(w -> w.text(null).text("").empty().number((BigDecimal) null).date(null).bool(null).endRow()))
                .isEqualTo(",,,,,\r\n");
    }

    @Test
    void tiengVietCoDauGiuNguyen() {
        assertThat(write(w -> w.text("Nguyễn Văn Ánh").text("Đắc Nhân Tâm").endRow()))
                .isEqualTo("Nguyễn Văn Ánh,Đắc Nhân Tâm\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=cmd|' /C calc'!A0", "+1", "-1", "@SUM(A1)", "\tx", "\rx"})
    void chongCsvInjectionChoOVanBan(String raw) {
        String escaped = CsvWriter.escape(raw);
        String inner = escaped.startsWith("\"") ? escaped.substring(1) : escaped;
        assertThat(inner).startsWith("'");
    }

    @Test
    void cacTienToCuThe() {
        assertThat(CsvWriter.escape("=cmd")).isEqualTo("'=cmd");
        assertThat(CsvWriter.escape("+1")).isEqualTo("'+1");
        assertThat(CsvWriter.escape("-1")).isEqualTo("'-1");
        assertThat(CsvWriter.escape("@x")).isEqualTo("'@x");
        assertThat(CsvWriter.escape("\tx")).isEqualTo("'\tx");
        assertThat(CsvWriter.escape("\rx")).isEqualTo("\"'\rx\"");
    }

    @Test
    void chongInjectionRoiMoiBoOKhiCoDauPhay() {
        assertThat(write(w -> w.text("=A1,B1").endRow())).isEqualTo("\"'=A1,B1\"\r\n");
    }

    @Test
    void khongThemTienToKhiKyTuTriggerKhongOViTriDau() {
        assertThat(CsvWriter.escape("a=b")).isEqualTo("a=b");
        assertThat(CsvWriter.escape("x-y")).isEqualTo("x-y");
        assertThat(CsvWriter.escape("'=da")).isEqualTo("'=da");
    }

    @Test
    void oSoKhongQuaBoChong() {
        assertThat(write(w -> w.number(-5).number(new BigDecimal("-1.50")).number(new BigDecimal("1E+3")).endRow()))
                .isEqualTo("-5,-1.50,1000\r\n");
    }

    @Test
    void ngayGioBoolean() {
        Instant t = Instant.parse("2026-11-17T17:30:05Z");
        assertThat(write(w -> w.date(LocalDate.of(2026, 11, 17)).timestamp(t, ZoneId.of("Asia/Ho_Chi_Minh"))
                .bool(true).bool(false).endRow()))
                .isEqualTo("2026-11-17,2026-11-18 00:30:05,true,false\r\n");
    }

    @Test
    void toStreamGhiBomUtf8TruocNoiDung() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvWriter w = CsvWriter.toStream(out);
        w.header("Tên").text("Ánh").endRow();
        w.flush();
        byte[] bytes = out.toByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo("Tên\r\nÁnh\r\n");
    }

    @Test
    void toStreamChiCoMotBom() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvWriter w = CsvWriter.toStream(out);
        w.text("a").endRow();
        w.text("b").endRow();
        w.flush();
        assertThat(new String(out.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("﻿a\r\nb\r\n");
    }

    @Test
    void toWriterKhongThemBom() {
        assertThat(write(w -> w.text("a").endRow())).doesNotStartWith("﻿");
    }

    @Test
    void dongRongChiCoCRLF() {
        assertThat(write(CsvWriter::endRow)).isEqualTo("\r\n");
    }

    @Test
    void loiIoDuocBocThanhUnchecked() {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("hỏng");
            }
        };
        assertThatThrownBy(() -> CsvWriter.toStream(broken)).isInstanceOf(UncheckedIOException.class);
    }
}
