package lms.identity.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lms.common.kernel.DomainEvent;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase;
import lms.identity.tenantdata.application.ExportPolicy;
import lms.identity.tenantdata.application.ExportSource;
import lms.identity.tenantdata.application.ExportViews.ExportView;
import lms.identity.tenantdata.application.MaintainTenantDataExportsUseCase;
import lms.identity.tenantdata.application.QueryTenantDataExportUseCase;
import lms.identity.tenantdata.application.RequestTenantDataExportUseCase;
import lms.identity.tenantdata.application.RunTenantDataExportUseCase;
import lms.identity.tenantdata.domain.ExportStatus;
import lms.identity.tenantdata.domain.PartStatus;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportException;
import lms.identity.tenantdata.domain.TenantDataExportException.Reason;
import lms.identity.tenantdata.domain.TenantDataExportReadyEvent;
import lms.identity.tenantdata.infrastructure.security.JacksonManifestCodec;
import lms.identity.tenantdata.infrastructure.security.JwsManifestSigner;
import lms.identity.tenantdata.infrastructure.storage.LocalFileExportStorage;
import lms.security.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Luồng xuất dữ liệu tenant với nguồn giả, lưu trữ thật (thư mục tạm, AES-GCM thật), ký JWS thật và repository trong bộ nhớ
 * (ADR-0031 F2): giới hạn tần suất, trạng thái, manifest + SHA-256, chữ ký kiểm được, file mã hóa, thử lại, lỗi một nguồn,
 * hết hạn xóa file, IDOR. Không cần Docker.
 */
class TenantDataExportFlowTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_HOST_DETAIL = "jdbc:postgresql://internal-db.lan:5432/secret_db password=hunter2";

    @TempDir Path dir;

    InMemoryRepos.Exports exports;
    InMemoryRepos.Parts parts;
    LocalFileExportStorage storage;
    RSAKey rsa;
    MutableClock clock;
    List<DomainEvent> events;
    ExportPolicy policy;
    List<ExportSource> sources;
    List<String> notConfigured;

    RequestTenantDataExportUseCase request;
    QueryTenantDataExportUseCase query;
    DownloadTenantDataExportUseCase download;
    MaintainTenantDataExportsUseCase maintain;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-19T01:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @BeforeEach
    void setUp() throws Exception {
        exports = new InMemoryRepos.Exports();
        parts = new InMemoryRepos.Parts();
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        storage = new LocalFileExportStorage(dir, Base64.getEncoder().encodeToString(keyBytes));
        rsa = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        clock = new MutableClock();
        events = new ArrayList<>();
        policy = new ExportPolicy(Duration.ofHours(24), Duration.ofHours(72), 10_000_000L, Duration.ofHours(2), 3,
                Duration.ZERO, Duration.ofMinutes(10));
        sources = new ArrayList<>(List.of(csvSource("identity", Map.of("users", "id,username\r\n1,alice\r\n2,bob\r\n")),
                csvSource("catalog", Map.of("titles", "id,title\r\n9,\"Sách, hay\"\r\n"))));
        notConfigured = List.of("sales");
        wire();
    }

    void wire() {
        PublishPlatformEventPort publisher = events::add;
        request = new RequestTenantDataExportUseCase(exports, storage, id -> java.util.Optional.of("demo"), policy,
                publisher, clock);
        query = new QueryTenantDataExportUseCase(exports, parts);
        download = new DownloadTenantDataExportUseCase(exports, storage, publisher, clock);
        maintain = new MaintainTenantDataExportsUseCase(exports, storage, policy, publisher, clock);
    }

    RunTenantDataExportUseCase runner() {
        return new RunTenantDataExportUseCase(exports, parts, sources, notConfigured, storage,
                id -> java.util.Optional.of("demo"), t -> List.of(ADMIN, UUID.fromString("00000000-0000-0000-0000-000000000009")),
                policy, new JacksonManifestCodec(), new JwsManifestSigner(rsa), events::add, "test", clock);
    }

    // ===== nguồn giả =====

    /** Nguồn ghi các CSV cố định; rows tính theo số dòng dữ liệu. */
    static ExportSource csvSource(String name, Map<String, String> tables) {
        return new ExportSource() {
            @Override public String name() { return name; }

            @Override
            public void export(UUID tenantId, UUID exportId, TableSink sink) {
                sink.schemaVersion(1);
                tables.forEach((table, csv) -> {
                    try (OutputStream out = sink.openFile(table + ".csv")) {
                        out.write(("﻿" + csv).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    long rows = csv.lines().count() - 1;
                    sink.table(table, table + ".csv", rows, List.of("c1", "c2"));
                });
                sink.excluded("secrets_" + name, "Bí mật");
            }
        };
    }

    /** Nguồn lỗi n lần đầu rồi thành công (mô phỏng exporter tạm thời 503). */
    static ExportSource flaky(String name, int failTimes, String code) {
        int[] calls = {0};
        ExportSource ok = csvSource(name, Map.of("titles", "id,title\r\n1,a\r\n"));
        return new ExportSource() {
            @Override public String name() { return name; }

            @Override
            public void export(UUID tenantId, UUID exportId, TableSink sink) {
                if (++calls[0] <= failTimes) {
                    // ghi dở rồi lỗi: spool của lần thử này phải bị bỏ, không làm trùng entry
                    try (OutputStream out = sink.openFile("titles.csv")) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    throw new SourceFailure(code, new RuntimeException(SECRET_HOST_DETAIL));
                }
                ok.export(tenantId, exportId, sink);
            }
        };
    }

    UUID requestAndRun(UUID tenant) {
        ExportView v = request.request(tenant, ADMIN, false, "chuyển hệ thống");
        runner().run(v.id());
        return v.id();
    }

    static Map<String, byte[]> unzip(InputStream in) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                out.put(e.getName(), zip.readAllBytes());
            }
        }
        return out;
    }

    Map<String, byte[]> downloadZip(UUID tenant, UUID id) throws IOException {
        try (InputStream in = download.open(tenant, id, ADMIN, false).stream()) {
            return unzip(in);
        }
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    // ===== yêu cầu: giới hạn và trạng thái =====

    @Test
    void yeuCauTaoPendingVaChiMotYeuCauDangChayMoiTenant() {
        ExportView v = request.request(TENANT_A, ADMIN, false, "  ");
        assertThat(v.status()).isEqualTo(ExportStatus.PENDING);
        assertThat(v.requestedByPlatform()).isFalse();
        assertThat(v.reason()).isNull();
        assertThatThrownBy(() -> request.request(TENANT_A, ADMIN, false, null))
                .isInstanceOfSatisfying(TenantDataExportException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.EXPORT_IN_PROGRESS));
        // tenant khác không bị ảnh hưởng
        assertThat(request.request(TENANT_B, ADMIN, false, null).status()).isEqualTo(ExportStatus.PENDING);
    }

    @Test
    void motLanThanhCongTrong24GioThiLanSauBi429ConSau24GioThiDuoc() {
        requestAndRun(TENANT_A);
        assertThatThrownBy(() -> request.request(TENANT_A, ADMIN, false, null))
                .isInstanceOfSatisfying(TenantDataExportException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.EXPORT_RATE_LIMITED));
        clock.advance(Duration.ofHours(25));
        assertThat(request.request(TENANT_A, ADMIN, false, null).status()).isEqualTo(ExportStatus.PENDING);
    }

    @Test
    void lanThatBaiKhongTinhVaoGioiHanVaPlatformAdminBoQuaGioiHan() {
        sources.set(1, flaky("catalog", 99, "SOURCE_HTTP_503"));
        UUID failed = requestAndRun(TENANT_A);
        assertThat(exports.rows.get(failed).status()).isEqualTo(ExportStatus.FAILED);
        // FAILED không chiếm chỗ, không tính giới hạn
        assertThat(request.request(TENANT_A, ADMIN, false, null).status()).isEqualTo(ExportStatus.PENDING);
        // platform admin vẫn vượt giới hạn 24 giờ sau một lần thành công
        sources.set(1, csvSource("catalog", Map.of("titles", "id\r\n1\r\n")));
        exports.rows.values().removeIf(e -> e.status() == ExportStatus.PENDING);
        requestAndRun(TENANT_A);
        ExportView byPlatform = request.request(TENANT_A, UUID.randomUUID(), true, "chấm dứt hợp đồng");
        assertThat(byPlatform.requestedByPlatform()).isTrue();
    }

    @Test
    void chuaCauHinhKhoaMaHoaThiTuChoi503() {
        LocalFileExportStorage unconfigured = new LocalFileExportStorage(dir, "");
        assertThat(unconfigured.configured()).isFalse();
        RequestTenantDataExportUseCase off = new RequestTenantDataExportUseCase(exports, unconfigured,
                id -> java.util.Optional.of("demo"), policy, events::add, clock);
        assertThatThrownBy(() -> off.request(TENANT_A, ADMIN, false, null))
                .isInstanceOfSatisfying(TenantDataExportException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.EXPORT_NOT_CONFIGURED));
        assertThat(exports.rows).isEmpty();
    }

    @Test
    void khoaMaHoaSaiDinhDangLamKhoiTaoThatBai() {
        assertThatThrownBy(() -> new LocalFileExportStorage(dir, "khong-phai-base64-!!"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalFileExportStorage(dir, Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 byte");
    }

    @Test
    void platformAdminXuatThayTenantKhongTonTaiBi404() {
        RequestTenantDataExportUseCase noTenant = new RequestTenantDataExportUseCase(exports, storage,
                id -> java.util.Optional.empty(), policy, events::add, clock);
        assertThatThrownBy(() -> noTenant.request(UUID.randomUUID(), ADMIN, true, null))
                .isInstanceOfSatisfying(TenantDataExportException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.TENANT_NOT_FOUND));
    }

    // ===== chạy: gói, manifest, chữ ký, mã hóa =====

    @Test
    void chayXongThanhReadyVoiGoiZipManifestDungSha256VaChuKyKiemDuoc() throws Exception {
        UUID id = requestAndRun(TENANT_A);

        TenantDataExport e = exports.rows.get(id);
        assertThat(e.status()).isEqualTo(ExportStatus.READY);
        assertThat(e.partsTotal()).isEqualTo(2);
        assertThat(e.partsDone()).isEqualTo(2);
        assertThat(e.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(72)));
        assertThat(e.sizeBytes()).isPositive();

        Map<String, byte[]> zip = downloadZip(TENANT_A, id);
        assertThat(zip).containsKeys("identity/users.csv", "catalog/titles.csv", "manifest.json", "manifest.json.sig",
                "README.txt");
        // BOM giữ nguyên, nội dung nguyên văn
        assertThat(new String(zip.get("catalog/titles.csv"), StandardCharsets.UTF_8))
                .isEqualTo("﻿id,title\r\n9,\"Sách, hay\"\r\n");

        // SHA-256 trong manifest khớp từng file và đủ thông tin
        JsonNode manifest = JSON.readTree(zip.get("manifest.json"));
        assertThat(manifest.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(manifest.get("tenantId").asText()).isEqualTo(TENANT_A.toString());
        assertThat(manifest.get("tenantCode").asText()).isEqualTo("demo");
        assertThat(manifest.get("exportId").asText()).isEqualTo(id.toString());
        assertThat(manifest.get("notConfigured")).extracting(JsonNode::asText).containsExactly("sales");
        int checked = 0;
        for (JsonNode section : manifest.get("sections")) {
            for (JsonNode f : section.get("files")) {
                byte[] content = zip.get(f.get("path").asText());
                assertThat(content).as(f.get("path").asText()).isNotNull();
                assertThat(f.get("sha256").asText()).isEqualTo(sha256(content));
                assertThat(f.get("bytes").asLong()).isEqualTo(content.length);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(2);
        JsonNode users = manifest.get("sections").get(0).get("files").get(0);
        assertThat(users.get("rows").asLong()).isEqualTo(2);
        assertThat(manifest.get("excluded")).hasSize(2);
        assertThat(zip.get("README.txt")).isNotEmpty();

        // chữ ký JWS tách rời kiểm được bằng khóa công khai, đúng kid; sửa manifest thì kiểm thất bại
        String sig = new String(zip.get("manifest.json.sig"), StandardCharsets.UTF_8);
        JWSObject jws = JWSObject.parse(sig, new Payload(zip.get("manifest.json")));
        assertThat(jws.getHeader().getKeyID()).isEqualTo("test-kid");
        assertThat(jws.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jws.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))).isTrue();
        byte[] tampered = zip.get("manifest.json").clone();
        tampered[tampered.length / 2] ^= 0x01;
        assertThat(JWSObject.parse(sig, new Payload(tampered)).verify(new RSASSAVerifier(rsa.toRSAPublicKey())))
                .isFalse();
    }

    @Test
    void fileTrenDiaDuocMaHoaVaPhatHienSuaFile() throws Exception {
        UUID id = requestAndRun(TENANT_A);
        Path file = dir.resolve(TenantDataExport.storageKeyOf(id));
        byte[] raw = Files.readAllBytes(file);
        // không phải ZIP thô và không chứa nội dung rõ
        assertThat(raw[0]).isNotEqualTo((byte) 'P');
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain("alice").doesNotContain("PK");
        // sửa một byte ở giữa: tải xuống/đọc phải lỗi, không trả dữ liệu hỏng im lặng
        raw[raw.length / 2] ^= 0x01;
        Files.write(file, raw);
        assertThatThrownBy(() -> downloadZip(TENANT_A, id)).isInstanceOf(IOException.class);
    }

    @Test
    void dungVaSpoolTamDuocDonSauKhiXong() throws Exception {
        UUID id = requestAndRun(TENANT_A);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactly(TenantDataExport.storageKeyOf(id));
        }
    }

    @Test
    void sauKhiXongPhatSuKienReadyChiCoIdVaGuiCaQuanTri() {
        UUID id = requestAndRun(TENANT_A);
        TenantDataExportReadyEvent ready = events.stream().filter(TenantDataExportReadyEvent.class::isInstance)
                .map(TenantDataExportReadyEvent.class::cast).findFirst().orElseThrow();
        assertThat(ready.exportId()).isEqualTo(id);
        assertThat(ready.tenantId()).isEqualTo(TENANT_A);
        assertThat(ready.requestedBy()).isEqualTo(ADMIN);
        assertThat(ready.recipientUserIds()).contains(ADMIN).hasSize(2);
        assertThat(ready.eventType()).isEqualTo("TenantDataExportReady");
        assertThat(ready.toString()).doesNotContain("@");
    }

    // ===== lỗi nguồn =====

    @Test
    void mocMotNguonLoiThiFailedBaoTungNguonVaKhongLoLoiNoiBo() {
        sources.set(1, flaky("catalog", 99, "SOURCE_HTTP_503"));
        UUID id = requestAndRun(TENANT_A);

        TenantDataExport e = exports.rows.get(id);
        assertThat(e.status()).isEqualTo(ExportStatus.FAILED);
        assertThat(e.errorCode()).isEqualTo("SOURCE_FAILED");
        assertThat(e.storageKey()).isNull();
        ExportView view = query.get(TENANT_A, id);
        assertThat(view.parts()).hasSize(2);
        assertThat(view.parts().get(1).service()).isEqualTo("catalog");
        assertThat(view.parts().get(1).status()).isEqualTo(PartStatus.FAILED);
        assertThat(view.parts().get(1).errorCode()).isEqualTo("SOURCE_HTTP_503");
        assertThat(view.parts().get(1).attempts()).isEqualTo(3);
        // nguồn tốt vẫn được thử và ghi DONE
        assertThat(view.parts().get(0).status()).isEqualTo(PartStatus.DONE);
        // không lộ thông điệp nội bộ ở bất kỳ đâu trong phản hồi
        assertThat(view.toString()).doesNotContain("hunter2").doesNotContain("internal-db");
        // file dở và spool bị xóa; không phát event READY
        assertThat(dir.toFile().list()).isEmpty();
        assertThat(events).noneMatch(TenantDataExportReadyEvent.class::isInstance);
        assertThatThrownBy(() -> download.open(TENANT_A, id, ADMIN, false))
                .isInstanceOfSatisfying(TenantDataExportException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.EXPORT_NOT_READY));
    }

    @Test
    void nguonLoiTamThoiDuocThuLaiVaKhongLamTrungEntry() throws Exception {
        sources.set(1, flaky("catalog", 2, "SOURCE_UNREACHABLE"));
        UUID id = requestAndRun(TENANT_A);
        assertThat(exports.rows.get(id).status()).isEqualTo(ExportStatus.READY);
        assertThat(query.get(TENANT_A, id).parts().get(1).attempts()).isEqualTo(3);
        Map<String, byte[]> zip = downloadZip(TENANT_A, id);
        assertThat(new String(zip.get("catalog/titles.csv"), StandardCharsets.UTF_8)).doesNotContain("partial");
    }

    @Test
    void vuotGioiHanKichThuocThiFailedSizeLimit() {
        policy = new ExportPolicy(Duration.ofHours(24), Duration.ofHours(72), 20L, Duration.ofHours(2), 3,
                Duration.ZERO, Duration.ofMinutes(10));
        wire();
        UUID id = requestAndRun(TENANT_A);
        TenantDataExport e = exports.rows.get(id);
        assertThat(e.status()).isEqualTo(ExportStatus.FAILED);
        assertThat(e.errorCode()).isEqualTo("SIZE_LIMIT");
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void loiNgoaiDuKienChiLuuMaChungKhongLuuThongDiep() {
        sources.set(1, new ExportSource() {
            @Override public String name() { return "catalog"; }

            @Override public void export(UUID t, UUID e, TableSink sink) {
                throw new IllegalStateException(SECRET_HOST_DETAIL);
            }
        });
        UUID id = requestAndRun(TENANT_A);
        TenantDataExport e = exports.rows.get(id);
        assertThat(e.status()).isEqualTo(ExportStatus.FAILED);
        assertThat(query.get(TENANT_A, id).toString()).doesNotContain("hunter2").doesNotContain("internal-db");
    }

    @Test
    void nguonTraSpoolKhongHopLeThiBiTuChoi() {
        sources.set(1, new ExportSource() {
            @Override public String name() { return "catalog"; }

            @Override public void export(UUID t, UUID e, TableSink sink) {
                try (OutputStream out = sink.openFile("../escape.csv")) {
                    out.write(1);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
        UUID id = requestAndRun(TENANT_A);
        assertThat(query.get(TENANT_A, id).parts().get(1).errorCode()).isEqualTo("SOURCE_INVALID");
    }

    // ===== hết hạn, kẹt, khởi động lại =====

    @Test
    void goiHetHanBiXoaFileVaChuyenExpired() throws Exception {
        UUID id = requestAndRun(TENANT_A);
        Path file = dir.resolve(TenantDataExport.storageKeyOf(id));
        assertThat(file).exists();
        clock.advance(Duration.ofHours(71));
        assertThat(maintain.expireDue()).isZero();
        assertThat(file).exists();
        clock.advance(Duration.ofHours(2));
        assertThat(maintain.expireDue()).isEqualTo(1);
        assertThat(file).doesNotExist();
        assertThat(exports.rows.get(id).status()).isEqualTo(ExportStatus.EXPIRED);
        assertThatThrownBy(() -> download.open(TENANT_A, id, ADMIN, false))
                .isInstanceOfSatisfying(TenantDataExportException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.EXPORT_EXPIRED));
        // EXPIRED vẫn tính là thành công trong 24 giờ gần nhất, nhưng quá 24 giờ thì tenant xuất lại được
        assertThat(request.request(TENANT_A, ADMIN, false, null).status()).isEqualTo(ExportStatus.PENDING);
    }

    @Test
    void goiQuaHanChuaDonVanKhongTaiDuoc() {
        UUID id = requestAndRun(TENANT_A);
        clock.advance(Duration.ofHours(73));
        assertThatThrownBy(() -> download.open(TENANT_A, id, ADMIN, false))
                .isInstanceOfSatisfying(TenantDataExportException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.EXPORT_EXPIRED));
    }

    @Test
    void runningQua2GioBiFailedStaleVaXoaFileDo() throws Exception {
        ExportView v = request.request(TENANT_A, ADMIN, false, null);
        assertThat(exports.claim(v.id(), clock.instant())).isTrue();
        Files.writeString(dir.resolve("spool-" + v.id() + "-catalog-1.zip.enc"), "x");
        clock.advance(Duration.ofHours(1));
        assertThat(maintain.failStale()).isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(maintain.failStale()).isEqualTo(1);
        assertThat(exports.rows.get(v.id()).status()).isEqualTo(ExportStatus.FAILED);
        assertThat(exports.rows.get(v.id()).errorCode()).isEqualTo("STALE");
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void sauKhoiDongLaiRunningDuocDuaVePendingVaChayLai() {
        ExportView v = request.request(TENANT_A, ADMIN, false, null);
        exports.claim(v.id(), clock.instant());
        assertThat(exports.rows.get(v.id()).status()).isEqualTo(ExportStatus.RUNNING);
        assertThat(maintain.recoverAfterRestart()).isEqualTo(1);
        assertThat(exports.rows.get(v.id()).status()).isEqualTo(ExportStatus.PENDING);
        assertThat(runner().run(v.id())).isTrue();
        assertThat(exports.rows.get(v.id()).status()).isEqualTo(ExportStatus.READY);
    }

    @Test
    void chiMotWorkerNhanDuocViecDangPending() {
        ExportView v = request.request(TENANT_A, ADMIN, false, null);
        assertThat(runner().run(v.id())).isTrue();
        assertThat(runner().run(v.id())).isFalse();
    }

    // ===== IDOR, tải, audit =====

    @Test
    void tenantKhacKhongXemKhongTaiDuocVaCungMotThanVoiIdKhongTonTai() {
        UUID id = requestAndRun(TENANT_A);
        UUID missing = UUID.randomUUID();
        TenantDataExportException other = catchEx(() -> query.get(TENANT_B, id));
        TenantDataExportException none = catchEx(() -> query.get(TENANT_B, missing));
        assertThat(other.reason()).isEqualTo(Reason.EXPORT_NOT_FOUND);
        assertThat(other.getMessage()).isEqualTo(none.getMessage());
        TenantDataExportException dl = catchEx(() -> download.open(TENANT_B, id, ADMIN, false));
        assertThat(dl.reason()).isEqualTo(Reason.EXPORT_NOT_FOUND);
        assertThat(dl.getMessage()).isEqualTo(none.getMessage());
        assertThat(query.list(TENANT_B)).isEmpty();
        assertThat(query.list(TENANT_A)).hasSize(1);
        assertThat(exports.downloads).isEmpty();
    }

    @Test
    void moiLanTaiGhiDemVaAuditCoTheTaiNhieuLanTrongHan() throws Exception {
        UUID id = requestAndRun(TENANT_A);
        downloadZip(TENANT_A, id);
        downloadZip(TENANT_A, id);
        assertThat(exports.downloads.get(id)).isEqualTo(2);
        long audits = events.stream().filter(e -> e.eventType().equals("PlatformOperationPerformed"))
                .map(Object::toString).filter(s -> s.contains("TENANT_DATA_EXPORT_DOWNLOADED")).count();
        assertThat(audits).isEqualTo(2);
        assertThat(events.stream().map(Object::toString))
                .anyMatch(s -> s.contains("TENANT_DATA_EXPORT_REQUESTED"));
    }

    @Test
    void danhSachNapTienDoTungNguonTheoLoMotLanKhongTheoTungDong() {
        requestAndRun(TENANT_A);
        clock.advance(Duration.ofHours(25));
        requestAndRun(TENANT_A);
        List<ExportView> list = query.list(TENANT_A);
        assertThat(list).hasSize(2);
        assertThat(list).allSatisfy(v -> assertThat(v.parts()).hasSize(2));
        assertThat(list.get(0).createdAt()).isAfterOrEqualTo(list.get(1).createdAt());
    }

    @Test
    void phanHoiKhongLoKhoaLuuTruVaKhongCoMaBam() {
        UUID id = requestAndRun(TENANT_A);
        String json = query.get(TENANT_A, id).toString();
        assertThat(json).doesNotContain(".zip.enc").doesNotContain("storage");
    }

    @Test
    void tokenSvcTenantDataChiDungChoExporterKhongPhaiLaTokenNguoiDung() {
        // hằng số khớp F0: token do identity phát cho exporter mang svc=tenant-data, aud=lms-tenant-data
        assertThat(JwtClaims.SERVICE_TENANT_DATA).isEqualTo("tenant-data");
        assertThat(JwtClaims.AUDIENCE_TENANT_DATA).isEqualTo("lms-tenant-data");
    }

    private static TenantDataExportException catchEx(Runnable r) {
        try {
            r.run();
        } catch (TenantDataExportException e) {
            return e;
        }
        throw new AssertionError("Mong đợi TenantDataExportException");
    }
}
