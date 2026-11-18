package lms.identity.tenantdata.infrastructure.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lms.identity.tenantdata.application.ExportStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lưu gói xuất ở thư mục cục bộ (volume riêng của identity, ADR-0031 D3), mã hóa AES-256-GCM theo khung. Khóa lấy từ cấu
 * hình (biến môi trường {@code TENANT_DATA_ENCRYPTION_KEY}, base64 của đúng 32 byte); rỗng thì {@link #configured()} là
 * false và mọi thao tác ghi/đọc bị từ chối. Ghi qua file {@code .part} rồi đổi tên nguyên tử khi đóng thành công, nên file
 * dở dang không bao giờ trông như file hoàn chỉnh. Chỉ đúng khi identity chạy MỘT instance (ADR-0031 D3).
 */
public class LocalFileExportStorage implements ExportStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileExportStorage.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    private final Path dir;
    private final SecretKey key;

    public LocalFileExportStorage(Path dir, String base64Key) {
        this.dir = dir.toAbsolutePath().normalize();
        this.key = parseKey(base64Key);
    }

    private static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getMimeDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TENANT_DATA_ENCRYPTION_KEY không phải base64 hợp lệ");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("TENANT_DATA_ENCRYPTION_KEY phải là base64 của đúng 32 byte (AES-256)");
        }
        return new SecretKeySpec(raw, "AES");
    }

    @Override
    public boolean configured() {
        return key != null;
    }

    private Path resolve(String name) {
        if (name == null || !KEY.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("Khóa lưu trữ không hợp lệ");
        }
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir)) {
            throw new IllegalArgumentException("Khóa lưu trữ không hợp lệ");
        }
        return p;
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("Chưa cấu hình khóa mã hóa gói xuất");
        }
    }

    @Override
    public OutputStream create(String name) throws IOException {
        requireConfigured();
        Files.createDirectories(dir);
        Path target = resolve(name);
        Path part = resolve(name + ".part");
        OutputStream file = new BufferedOutputStream(Files.newOutputStream(part, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), 64 * 1024);
        OutputStream enc;
        try {
            enc = ChunkedAesGcm.encrypt(file, key);
        } catch (IOException | RuntimeException e) {
            file.close();
            Files.deleteIfExists(part);
            throw e;
        }
        return new java.io.FilterOutputStream(enc) {
            private boolean closed;

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    super.close();
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(part);
                    throw e;
                }
            }
        };
    }

    @Override
    public InputStream open(String name) throws IOException {
        requireConfigured();
        InputStream file = new BufferedInputStream(Files.newInputStream(resolve(name)), 64 * 1024);
        try {
            return ChunkedAesGcm.decrypt(file, key);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    @Override
    public boolean exists(String name) {
        return Files.isRegularFile(resolve(name));
    }

    @Override
    public void delete(String name) {
        try {
            Files.deleteIfExists(resolve(name));
            Files.deleteIfExists(resolve(name + ".part"));
        } catch (IOException e) {
            log.warn("Không xóa được file lưu trữ {}: {}", name, e.toString());
        }
    }

    @Override
    public void deleteByPrefix(String prefix) {
        if (prefix == null || !KEY.matcher(prefix).matches() || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*")) {
            for (Path p : stream) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Không xóa được file tạm {}: {}", p.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Không duyệt được thư mục lưu trữ: {}", e.toString());
        }
    }
}
