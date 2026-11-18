package lms.identity.tenantdata.application;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Ghi CSV theo hợp đồng exporter (ADR-0031 phụ lục A): UTF-8 có BOM, RFC 4180, xuống dòng CRLF, null là ô rỗng.
 * Chỉ định dạng CSV; việc chống công thức bảng tính (tiền tố nháy đơn cho ô văn bản) do nguồn dữ liệu làm vì chỉ nguồn biết kiểu của ô.
 */
public final class CsvWriter implements AutoCloseable {

    private final BufferedWriter out;

    public CsvWriter(OutputStream stream) throws IOException {
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 32 * 1024);
        out.write('﻿');
    }

    public void row(List<String> cells) throws IOException {
        boolean first = true;
        for (String cell : cells) {
            if (!first) {
                out.write(',');
            }
            first = false;
            writeCell(cell);
        }
        out.write("\r\n");
    }

    private void writeCell(String raw) throws IOException {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String cell = raw;
        boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0
                || cell.indexOf('\r') >= 0;
        if (!quote) {
            out.write(cell);
            return;
        }
        out.write('"');
        out.write(cell.replace("\"", "\"\""));
        out.write('"');
    }

    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
