package lms.circulation.reporting.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.reporting.application.CopyLabelLookupPort;
import lms.circulation.reporting.application.ExportOverdueBorrowingsCsvUseCase;
import lms.circulation.reporting.application.LendingStatsPort;
import lms.circulation.reporting.application.ReaderNameLookupPort;
import lms.circulation.reporting.domain.DateRange;
import lms.circulation.reporting.domain.LendingCounts;
import lms.circulation.reporting.domain.OverdueCopy;
import lms.circulation.reporting.domain.TopTitle;
import lms.circulation.reporting.domain.UnpaidFines;
import org.junit.jupiter.api.Test;

/** CSV mượn quá hạn: BOM, tiêu đề, chống injection, trần dòng, tra tên theo lô, tra lỗi vẫn xuất đủ dòng. */
class ExportOverdueBorrowingsCsvUseCaseTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    // 2026-03-01 12:00 giờ VN
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T05:00:00Z"), VN);
    static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    final List<OverdueCopy> rows = new ArrayList<>();
    final Map<UUID, String> names = new HashMap<>();
    final Map<UUID, CopyLabelLookupPort.CopyLabel> labels = new HashMap<>();
    final List<Integer> nameBatchSizes = new ArrayList<>();
    final List<Integer> labelBatchSizes = new ArrayList<>();
    boolean lookupsFail;

    final LendingStatsPort stats = new LendingStatsPort() {
        public LendingCounts counts(LocalDate today) { return null; }
        public UnpaidFines unpaidFines() { return null; }
        public Map<LocalDate, Long> borrowedPerDay(DateRange range) { return Map.of(); }
        public Map<LocalDate, Long> returnedPerDay(DateRange range) { return Map.of(); }
        public List<TopTitle> topBorrowedTitles(DateRange range, int limit) { return List.of(); }
        public long countOverdueCopies(LocalDate today) { return rows.size(); }
        public List<OverdueCopy> overdueCopies(LocalDate today, int offset, int limit) {
            return rows.subList(Math.min(offset, rows.size()), Math.min(offset + limit, rows.size()));
        }
    };

    final ReaderNameLookupPort readerPort = ids -> {
        nameBatchSizes.add(ids.size());
        if (lookupsFail) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        for (UUID id : ids) if (names.containsKey(id)) out.put(id, names.get(id));
        return out;
    };

    final CopyLabelLookupPort copyPort = ids -> {
        labelBatchSizes.add(ids.size());
        if (lookupsFail) return Map.of();
        Map<UUID, CopyLabelLookupPort.CopyLabel> out = new HashMap<>();
        for (UUID id : ids) if (labels.containsKey(id)) out.put(id, labels.get(id));
        return out;
    };

    ExportOverdueBorrowingsCsvUseCase useCase(int maxRows) {
        return new ExportOverdueBorrowingsCsvUseCase(stats, readerPort, copyPort, CLOCK, maxRows);
    }

    OverdueCopy row(UUID user, UUID copy, int daysOverdue) {
        return new OverdueCopy(UUID.randomUUID(), user, copy, TODAY.minusDays(daysOverdue + 14L), TODAY.minusDays(daysOverdue));
    }

    byte[] write(ExportOverdueBorrowingsCsvUseCase.Export export) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        export.writeTo(out);
        return out.toByteArray();
    }

    static String text(byte[] bytes) {
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    void coBomUtf8TieuDeDungVaDongDuLieuCoTenDaTra() {
        UUID user = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        names.put(user, "Nguyễn Văn Á");
        labels.put(copy, new CopyLabelLookupPort.CopyLabel("Lập trình Java", "BC-001"));
        OverdueCopy r = row(user, copy, 5);
        rows.add(r);

        byte[] bytes = write(useCase(100).prepare());

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String[] lines = text(bytes).split("\r\n");
        assertThat(lines[0]).isEqualTo("borrowingId,userId,readerName,titleName,barcode,borrowDate,dueDate,daysOverdue");
        assertThat(lines[1]).isEqualTo(r.borrowingId() + "," + user + ",Nguyễn Văn Á,Lập trình Java,BC-001,"
                + r.borrowDate() + "," + r.dueDate() + ",5");
        assertThat(lines).hasSize(2);
    }

    @Test
    void khongXuatEmailHoacDienThoai() {
        rows.add(row(UUID.randomUUID(), UUID.randomUUID(), 1));

        String header = text(write(useCase(100).prepare())).split("\r\n")[0].toLowerCase();

        assertThat(header).doesNotContain("email", "phone", "tel", "mail");
    }

    @Test
    void chongCsvInjectionCuaTenDocGiaVaTenDauSach() {
        UUID user = UUID.randomUUID();
        UUID copy1 = UUID.randomUUID();
        UUID copy2 = UUID.randomUUID();
        names.put(user, "=cmd|' /C calc'!A0");
        labels.put(copy1, new CopyLabelLookupPort.CopyLabel("+1", "-5"));
        labels.put(copy2, new CopyLabelLookupPort.CopyLabel("@x, \"quoted\"", "BC"));
        rows.add(row(user, copy1, 2));
        rows.add(row(user, copy2, 2));

        String csv = text(write(useCase(100).prepare()));

        assertThat(csv).contains("'=cmd|' /C calc'!A0").contains(",'+1,'-5,").contains("\"'@x, \"\"quoted\"\"\"");
    }

    @Test
    void vuotTranThiCatDungMaxRowsVaDanhDauTruncated() {
        for (int i = 0; i < 5; i++) rows.add(row(UUID.randomUUID(), UUID.randomUUID(), 10 - i));

        ExportOverdueBorrowingsCsvUseCase.Export export = useCase(3).prepare();
        String[] lines = text(write(export)).split("\r\n");

        assertThat(export.truncated()).isTrue();
        assertThat(export.plannedRows()).isEqualTo(3);
        assertThat(lines).hasSize(1 + 3);
    }

    @Test
    void dungBangTranHoacItHonThiKhongCat() {
        for (int i = 0; i < 3; i++) rows.add(row(UUID.randomUUID(), UUID.randomUUID(), 3));

        assertThat(useCase(3).prepare().truncated()).isFalse();
        assertThat(useCase(10).prepare().truncated()).isFalse();
        assertThat(useCase(2).prepare().truncated()).isTrue();
    }

    @Test
    void khongCoDongNaoVanCoBomVaTieuDe() {
        ExportOverdueBorrowingsCsvUseCase.Export export = useCase(100).prepare();
        byte[] bytes = write(export);

        assertThat(export.plannedRows()).isZero();
        assertThat(text(bytes)).isEqualTo("borrowingId,userId,readerName,titleName,barcode,borrowDate,dueDate,daysOverdue\r\n");
    }

    @Test
    void traTenTheoLo100KhongGoiTungDong() {
        for (int i = 0; i < 250; i++) rows.add(row(UUID.randomUUID(), UUID.randomUUID(), 1 + i % 7));

        ExportOverdueBorrowingsCsvUseCase.Export export = useCase(10000).prepare();
        String csv = text(write(export));

        assertThat(csv.split("\r\n")).hasSize(251);
        assertThat(nameBatchSizes).containsExactly(100, 100, 50);
        assertThat(labelBatchSizes).containsExactly(100, 100, 50);
    }

    @Test
    void traTenLoiThiOTrongVaVanXuatDuDong() {
        lookupsFail = true;
        for (int i = 0; i < 3; i++) rows.add(row(UUID.randomUUID(), UUID.randomUUID(), 4));

        String[] lines = text(write(useCase(100).prepare())).split("\r\n");

        assertThat(lines).hasSize(4);
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            assertThat(cells).hasSize(8);
            assertThat(cells[2]).isEmpty();
            assertThat(cells[3]).isEmpty();
            assertThat(cells[4]).isEmpty();
            assertThat(cells[7]).isEqualTo("4");
        }
    }

    @Test
    void tenFileTheoNgayGioVn() {
        // 00:10 giờ VN ngày 2026-03-02 = 17:10 UTC ngày 2026-03-01
        Clock after = Clock.fixed(Instant.parse("2026-03-01T17:10:00Z"), VN);
        ExportOverdueBorrowingsCsvUseCase u = new ExportOverdueBorrowingsCsvUseCase(stats, readerPort, copyPort, after, 100);

        assertThat(u.prepare().fileName()).isEqualTo("overdue-borrowings-20260302.csv");
        assertThat(useCase(100).prepare().fileName()).isEqualTo("overdue-borrowings-20260301.csv");
    }

    @Test
    void doTreChinhXacTheoNgayHomNay() {
        UUID user = UUID.randomUUID();
        rows.add(row(user, UUID.randomUUID(), 1));
        rows.add(row(user, UUID.randomUUID(), 30));

        String[] lines = text(write(useCase(100).prepare())).split("\r\n");

        assertThat(lines[1]).endsWith(",1");
        assertThat(lines[2]).endsWith(",30");
    }
}
