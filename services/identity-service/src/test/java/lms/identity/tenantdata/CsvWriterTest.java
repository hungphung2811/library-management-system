package lms.identity.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import lms.identity.tenantdata.application.CsvWriter;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void ghiBomUtf8TieuDeCrlfVaTrichDanDungRfc4180() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CsvWriter csv = new CsvWriter(bytes)) {
            csv.row(List.of("id", "ten", "ghi_chu"));
            csv.row(Arrays.asList("1", "Nguyễn, Văn \"A\"", null));
            csv.row(List.of("2", "dòng 1\ndòng 2", "x"));
        }
        byte[] raw = bytes.toByteArray();
        assertThat(raw[0]).isEqualTo((byte) 0xEF);
        assertThat(raw[1]).isEqualTo((byte) 0xBB);
        assertThat(raw[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8)).isEqualTo(
                "id,ten,ghi_chu\r\n1,\"Nguyễn, Văn \"\"A\"\"\",\r\n2,\"dòng 1\ndòng 2\",x\r\n");
    }
}
