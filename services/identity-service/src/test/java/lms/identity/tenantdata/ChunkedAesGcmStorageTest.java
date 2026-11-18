package lms.identity.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import lms.identity.tenantdata.infrastructure.storage.LocalFileExportStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AES-256-GCM theo khung: khứ hồi, nhiều khung, rỗng, phát hiện sửa/cắt/sai khóa, khóa lưu trữ an toàn đường dẫn. */
class ChunkedAesGcmStorageTest {

    @TempDir Path dir;

    String keyA;
    String keyB;
    LocalFileExportStorage storage;

    @BeforeEach
    void setUp() {
        keyA = randomKey();
        keyB = randomKey();
        storage = new LocalFileExportStorage(dir, keyA);
    }

    static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    static byte[] data(int n) {
        byte[] d = new byte[n];
        new SecureRandom().nextBytes(d);
        return d;
    }

    void write(String key, byte[] bytes) throws IOException {
        try (OutputStream out = storage.create(key)) {
            // ghi từng mảnh nhỏ để kiểm tra việc gom khung
            for (int i = 0; i < bytes.length; i += 7001) {
                out.write(bytes, i, Math.min(7001, bytes.length - i));
            }
        }
    }

    byte[] read(LocalFileExportStorage s, String key) throws IOException {
        try (InputStream in = s.open(key)) {
            return in.readAllBytes();
        }
    }

    @Test
    void khuHoiNhieuKichThuocKeCaRongVaBienGioiKhung() throws IOException {
        for (int n : new int[] {0, 1, 65535, 65536, 65537, 65536 * 2, 65536 * 3 + 17, 1_000_000}) {
            byte[] plain = data(n);
            write("file-" + n + ".bin", plain);
            assertThat(read(storage, "file-" + n + ".bin")).as("n=" + n).isEqualTo(plain);
        }
    }

    @Test
    void khongLuuNoiDungRoTrenDiaVaMoiLanGhiCoNonceKhac() throws IOException {
        byte[] plain = "du lieu nhay cam cua tenant".repeat(100).getBytes();
        write("a.bin", plain);
        write("b.bin", plain);
        byte[] a = Files.readAllBytes(dir.resolve("a.bin"));
        byte[] b = Files.readAllBytes(dir.resolve("b.bin"));
        assertThat(new String(a, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("nhay cam");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void suaMotByteBiPhatHien() throws IOException {
        write("t.bin", data(200_000));
        Path p = dir.resolve("t.bin");
        for (int pos : new int[] {20, 70_000, 150_000}) {
            byte[] raw = Files.readAllBytes(p);
            raw[pos] ^= 0x01;
            Files.write(p, raw);
            assertThatThrownBy(() -> read(storage, "t.bin")).isInstanceOf(IOException.class);
            raw[pos] ^= 0x01;
            Files.write(p, raw);
        }
        assertThat(read(storage, "t.bin")).hasSize(200_000);
    }

    @Test
    void catDuoiFileBiPhatHienKeCaKhiCatDungRanhGioiKhung() throws IOException {
        write("c.bin", data(200_000));
        Path p = dir.resolve("c.bin");
        byte[] raw = Files.readAllBytes(p);
        // cắt đúng sau khung đầu (13 byte tiêu đề + 4 + 65536 + 16)
        int firstFrameEnd = 13 + 4 + 65536 + 16;
        Files.write(p, Arrays.copyOf(raw, firstFrameEnd));
        assertThatThrownBy(() -> read(storage, "c.bin")).isInstanceOf(IOException.class);
        Files.write(p, Arrays.copyOf(raw, raw.length - 1));
        assertThatThrownBy(() -> read(storage, "c.bin")).isInstanceOf(IOException.class);
        Files.write(p, Arrays.copyOf(raw, 5));
        assertThatThrownBy(() -> read(storage, "c.bin")).isInstanceOf(IOException.class);
    }

    @Test
    void doiThuTuKhungBiPhatHien() throws IOException {
        write("o.bin", data(65536 * 2 + 10));
        Path p = dir.resolve("o.bin");
        byte[] raw = Files.readAllBytes(p);
        int frame = 4 + 65536 + 16;
        byte[] swapped = raw.clone();
        System.arraycopy(raw, 13 + frame, swapped, 13, frame);
        System.arraycopy(raw, 13, swapped, 13 + frame, frame);
        Files.write(p, swapped);
        assertThatThrownBy(() -> read(storage, "o.bin")).isInstanceOf(IOException.class);
    }

    @Test
    void saiKhoaKhongGiaiMaDuoc() throws IOException {
        write("k.bin", data(1000));
        LocalFileExportStorage other = new LocalFileExportStorage(dir, keyB);
        assertThatThrownBy(() -> read(other, "k.bin")).isInstanceOf(IOException.class);
    }

    @Test
    void khoaLuuTruKhongHopLeBiTuChoiChongPathTraversal() {
        for (String bad : new String[] {"../x", "a/b", "a\\b", "..", "x..y", "", "a b", "/etc/passwd"}) {
            assertThatThrownBy(() -> storage.create(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.open(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void fileDangGhiDoKhongTrongNhuHoanChinhVaXoaTheoTienTo() throws IOException {
        OutputStream out = storage.create("spool-1-a.bin");
        out.write(data(10));
        assertThat(storage.exists("spool-1-a.bin")).isFalse();
        out.close();
        assertThat(storage.exists("spool-1-a.bin")).isTrue();
        write("spool-1-b.bin", data(5));
        write("keep.bin", data(5));
        storage.deleteByPrefix("spool-1-");
        assertThat(storage.exists("spool-1-a.bin")).isFalse();
        assertThat(storage.exists("spool-1-b.bin")).isFalse();
        assertThat(storage.exists("keep.bin")).isTrue();
    }

    @Test
    void chuaCauHinhKhoaThiGhiDocBiTuChoi() {
        LocalFileExportStorage off = new LocalFileExportStorage(dir, " ");
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.create("a.bin")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> off.open("a.bin")).isInstanceOf(IllegalStateException.class);
    }
}
