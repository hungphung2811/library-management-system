package lms.identity.tenantdata.application;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import lms.identity.tenantdata.application.ManifestModel.Excluded;
import lms.identity.tenantdata.application.ManifestModel.Manifest;
import lms.identity.tenantdata.application.ManifestModel.Section;
import lms.identity.tenantdata.domain.ExportedTable;
import lms.identity.tenantdata.domain.TenantDataExport;

/**
 * Dựng gói ZIP cuối: chép các spool của từng nguồn vào {@code <nguồn>/<bảng>.csv}, thêm {@code README.txt},
 * {@code manifest.json} (SHA-256 từng CSV) và {@code manifest.json.sig} (JWS tách rời), ghi thẳng ra lưu trữ mã hóa theo
 * luồng (không giữ gói trong RAM) và tính SHA-256 + kích thước của ZIP.
 */
final class ExportPackager {

    static final String MANIFEST = "manifest.json";
    static final String SIGNATURE = "manifest.json.sig";
    static final String README = "README.txt";

    /** Phần đã xong của một nguồn, sẵn sàng chép vào gói. */
    record PartOutput(String service, int schemaVersion, String spoolKey, List<ExportedTable> tables,
                      List<PartSpool.Excluded> excluded) {
    }

    record Packaged(long sizeBytes, String sha256) {
    }

    private final ExportStoragePort storage;
    private final ManifestCodec codec;
    private final ManifestSignerPort signer;
    private final String appVersion;

    ExportPackager(ExportStoragePort storage, ManifestCodec codec, ManifestSignerPort signer, String appVersion) {
        this.storage = storage;
        this.codec = codec;
        this.signer = signer;
        this.appVersion = appVersion;
    }

    Packaged build(TenantDataExport export, String tenantCode, String key, List<PartOutput> parts,
                   List<String> notConfigured, Instant now) throws IOException {
        List<Section> sections = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (PartOutput p : parts) {
            List<ManifestModel.File> files = p.tables().stream()
                    .map(t -> new ManifestModel.File(p.service() + "/" + t.file(), t.name(), t.rows(), t.bytes(),
                            t.sha256(), t.columns()))
                    .sorted(Comparator.comparing(ManifestModel.File::path)).toList();
            sections.add(new Section(p.service(), p.schemaVersion(), files));
            p.excluded().forEach(e -> excluded.add(new Excluded(p.service(), e.table(), e.reason())));
        }
        Manifest manifest = new Manifest(ManifestModel.SCHEMA_VERSION, export.id(), export.tenantId(), tenantCode,
                export.requestedBy(), export.requestedByPlatform(), export.createdAt(), now, sections, excluded,
                List.copyOf(notConfigured), appVersion);
        byte[] manifestJson = codec.toJson(manifest);
        String signature = signer.signDetached(manifestJson);

        MessageDigest sha = digest();
        Counting counting;
        try (OutputStream raw = storage.create(key)) {
            counting = new Counting(new DigestOutputStream(raw, sha));
            try (ZipOutputStream zip = new ZipOutputStream(counting)) {
                byte[] buf = new byte[64 * 1024];
                for (PartOutput p : parts) {
                    copyPart(zip, p, buf);
                }
                put(zip, README, readme(export, tenantCode, now, manifest).getBytes(StandardCharsets.UTF_8));
                put(zip, MANIFEST, manifestJson);
                put(zip, SIGNATURE, signature.getBytes(StandardCharsets.UTF_8));
            }
        }
        return new Packaged(counting.count, HexFormat.of().formatHex(sha.digest()));
    }

    private void copyPart(ZipOutputStream zip, PartOutput p, byte[] buf) throws IOException {
        if (p.tables().isEmpty() || !storage.exists(p.spoolKey())) {
            return;
        }
        try (InputStream in = storage.open(p.spoolKey()); ZipInputStream zin = new ZipInputStream(in)) {
            for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                zip.putNextEntry(new ZipEntry(p.service() + "/" + e.getName()));
                int n;
                while ((n = zin.read(buf)) > 0) {
                    zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    static String readme(TenantDataExport export, String tenantCode, Instant now, Manifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("GÓI XUẤT DỮ LIỆU CỦA TENANT\r\n");
        sb.append("============================\r\n\r\n");
        sb.append("Tenant: ").append(tenantCode).append(" (").append(export.tenantId()).append(")\r\n");
        sb.append("Mã yêu cầu: ").append(export.id()).append("\r\n");
        sb.append("Thời điểm tạo gói: ").append(now).append("\r\n\r\n");
        sb.append("Cấu trúc\r\n--------\r\n");
        sb.append("- Mỗi thư mục là dữ liệu của một dịch vụ; mỗi bảng là một file CSV (UTF-8 có BOM, mở được bằng Excel).\r\n");
        sb.append("- Dòng đầu mỗi CSV là tên cột. Cột dạng JSON được ghi thành chuỗi JSON. Thời điểm theo ISO-8601 UTC.\r\n");
        sb.append("- manifest.json liệt kê từng file với số dòng, số byte và SHA-256.\r\n");
        sb.append("- manifest.json.sig là chữ ký JWS (RS256) tách rời của manifest.json.\r\n\r\n");
        sb.append("Kiểm tra tính toàn vẹn\r\n----------------------\r\n");
        sb.append("1. Tính SHA-256 từng file và so với manifest.json.\r\n");
        sb.append("2. Kiểm chữ ký: JWS compact tách rời, payload là chính các byte của manifest.json (RFC 7515 phụ lục F); ");
        sb.append("khóa công khai lấy từ JWKS của nhà cung cấp theo trường kid trong header của chữ ký.\r\n\r\n");
        sb.append("Không có trong gói (có chủ đích)\r\n--------------------------------\r\n");
        sb.append("Mật khẩu băm, token đăng nhập/làm mới, bí mật của ứng dụng tích hợp, khóa nội bộ và các dữ liệu ")
                .append("toàn cục của nền tảng. Chi tiết từng bảng bị loại nằm trong manifest.json, mục excluded.\r\n\r\n");
        if (!manifest.notConfigured().isEmpty()) {
            sb.append("Nguồn chưa được cấu hình khi tạo gói: ").append(String.join(", ", manifest.notConfigured()))
                    .append("\r\n\r\n");
        }
        sb.append("Gói này chứa dữ liệu cá nhân; hãy bảo quản an toàn. Liên kết tải hết hạn sau thời gian giữ gói.\r\n");
        return sb.toString();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Counting extends FilterOutputStream {

        long count;

        Counting(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
