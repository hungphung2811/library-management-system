package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.circulation.borrowing.application.BorrowBookCommand;
import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.CopyInventoryPort;
import lms.circulation.borrowing.application.CopyReservationPort;
import lms.circulation.borrowing.application.DependencyUnavailableException;
import lms.circulation.borrowing.application.FineLedgerPort;
import lms.circulation.borrowing.application.ReaderDirectoryPort;
import lms.circulation.borrowing.application.ReportLostCommand;
import lms.circulation.borrowing.application.ReportLostUseCase;
import lms.circulation.borrowing.application.ReturnBookCommand;
import lms.circulation.borrowing.application.ReturnBookUseCase;
import lms.circulation.borrowing.domain.BorrowRule;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.circulation.borrowing.domain.BorrowingDomainService;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingLimitExceededException;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRejectedException;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.CopyStatus;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.circulation.borrowing.domain.NotFoundException;
import lms.circulation.borrowing.domain.ReaderType;
import lms.circulation.fine.application.AssessFineCommand;
import lms.circulation.fine.application.AssessFineUseCase;
import lms.circulation.fine.application.CollectFinesUseCase;
import lms.circulation.fine.application.FineResponse;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineCalculator;
import lms.circulation.fine.domain.FineRates;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineStatus;
import lms.common.kernel.DomainEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UC06-UC09 với fake thay cho catalog, identity, reservation và CSDL (không cần Docker). */
class BorrowBookUseCaseTest {

    static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    final Clock clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    // ---- fake ----
    final Map<UUID, BorrowingRecord> records = new HashMap<>();
    final Map<UUID, CopyInventoryPort.CopyInfo> copies = new HashMap<>();
    final Map<UUID, ReaderDirectoryPort.ReaderInfo> readers = new HashMap<>();
    final Map<UUID, UUID> holders = new HashMap<>(); // copyId -> readerId đang được giữ
    final List<String> fulfilled = new ArrayList<>();
    final List<Fine> fines = new ArrayList<>();
    final List<DomainEvent> events = new ArrayList<>();
    UUID failStatusChangeFor;

    final BorrowingRepository repository = new BorrowingRepository() {
        public Optional<BorrowingRecord> findById(BorrowingId id) { return Optional.ofNullable(records.get(id.value())); }
        public Optional<BorrowingRecord> findByIdAndUserId(BorrowingId id, UUID userId) {
            return findById(id).filter(r -> r.userId().equals(userId));
        }
        public Optional<BorrowingRecord> findByIdForUpdate(BorrowingId id) { return findById(id); }
        public Optional<BorrowingRecord> findOpenByCopyIdForUpdate(UUID copyId) {
            return records.values().stream().filter(r -> r.items().stream()
                    .anyMatch(i -> i.bookCopyId().equals(copyId) && !i.isReturned())).findFirst();
        }
        public List<BorrowingRecord> findByUserId(UUID userId) {
            return records.values().stream().filter(r -> r.userId().equals(userId)).toList();
        }
        public long countActiveItemsByUserId(UUID userId) {
            return records.values().stream().filter(r -> r.userId().equals(userId)).mapToLong(BorrowingRecord::activeItemCount).sum();
        }
        public long countOverdueItemsByUserId(UUID userId, LocalDate today) {
            return records.values().stream().filter(r -> r.userId().equals(userId) && r.isOverdue(today))
                    .mapToLong(BorrowingRecord::activeItemCount).sum();
        }
        public lms.circulation.borrowing.domain.BorrowingSearchResult search(
                lms.circulation.borrowing.domain.BorrowingSearchCriteria criteria) {
            throw new UnsupportedOperationException();
        }
    };

    final BorrowRuleRepository rules = new BorrowRuleRepository() {
        public List<BorrowRule> findAll() {
            return List.of(new BorrowRule(ReaderType.STUDENT, 5, 14), new BorrowRule(ReaderType.LECTURER, 10, 30));
        }
        public boolean addIfAbsent(BorrowRule rule) { return false; }
    };

    final ReaderDirectoryPort readerPort = id -> Optional.ofNullable(readers.get(id));

    final CopyInventoryPort inventory = new CopyInventoryPort() {
        public Optional<CopyInfo> findById(UUID id) { return Optional.ofNullable(copies.get(id)); }
        public Optional<CopyInfo> findByBarcode(String barcode) {
            return copies.values().stream().filter(c -> c.barcode().equals(barcode)).findFirst();
        }
        public void changeStatus(UUID id, CopyStatus target) {
            if (id.equals(failStatusChangeFor) && target == CopyStatus.LOANED) {
                throw new DependencyUnavailableException("catalog-service", null);
            }
            CopyInfo c = copies.get(id);
            copies.put(id, new CopyInfo(c.id(), c.titleId(), c.titleName(), c.barcode(), target, c.coverPrice()));
        }
    };

    final CopyReservationPort reservationPort = new CopyReservationPort() {
        public Optional<UUID> holderOf(UUID copyId) { return Optional.ofNullable(holders.get(copyId)); }
        public void fulfill(UUID copyId, UUID readerId) {
            if (readerId.equals(holders.get(copyId))) fulfilled.add(copyId + ":" + readerId);
        }
    };

    final FineRepository fineRepository = new FineRepository() {
        public Fine save(Fine f) { fines.removeIf(x -> x.id().equals(f.id())); fines.add(f); return f; }
        public boolean exists(UUID b, UUID c, FineReason r) {
            return fines.stream().anyMatch(f -> f.borrowingId().equals(b) && f.bookCopyId().equals(c) && f.reason() == r);
        }
        public List<Fine> findByReader(UUID reader) { return fines.stream().filter(f -> f.readerId().equals(reader)).toList(); }
        public List<Fine> findUnpaidByReaderForUpdate(UUID reader) {
            return fines.stream().filter(f -> f.readerId().equals(reader) && f.isUnpaid()).toList();
        }
        public BigDecimal sumUnpaidByReader(UUID reader) {
            return findUnpaidByReaderForUpdate(reader).stream().map(Fine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    };

    final AssessFineUseCase assessFine = new AssessFineUseCase(new FineCalculator(FineRates.standard()),
            fineRepository, events::add, clock);
    final CollectFinesUseCase collectFines = new CollectFinesUseCase(fineRepository, events::add, clock);

    final FineLedgerPort ledger = new FineLedgerPort() {
        public BigDecimal unpaidTotal(UUID readerId) { return fineRepository.sumUnpaidByReader(readerId); }
        public List<FineResponse> assess(AssessFineCommand command) { return assessFine.execute(command); }
    };

    final BorrowingRepository repo = repository;
    final lms.circulation.borrowing.application.SaveBorrowingPort save = r -> { records.put(r.id().value(), r); return r; };

    final BorrowBookUseCase borrow = new BorrowBookUseCase(new BorrowingDomainService(), repo, save, rules, readerPort,
            inventory, reservationPort, ledger, events::add, clock);
    final ReturnBookUseCase giveBack = new ReturnBookUseCase(repo, save, inventory, ledger, events::add, clock);
    final ReportLostUseCase lose = new ReportLostUseCase(repo, save, inventory, ledger, events::add, clock);

    UUID student;
    UUID lecturer;

    @BeforeEach
    void setUp() {
        student = UUID.randomUUID();
        lecturer = UUID.randomUUID();
        readers.put(student, new ReaderDirectoryPort.ReaderInfo(student, ReaderType.STUDENT, true));
        readers.put(lecturer, new ReaderDirectoryPort.ReaderInfo(lecturer, ReaderType.LECTURER, true));
    }

    UUID copy(CopyStatus status, String price) {
        UUID id = UUID.randomUUID();
        copies.put(id, new CopyInventoryPort.CopyInfo(id, UUID.randomUUID(), "Sách", "BC-" + id.toString().substring(0, 6),
                status, new BigDecimal(price)));
        return id;
    }

    List<UUID> available(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(copy(CopyStatus.AVAILABLE, "100000"));
        return ids;
    }

    /** Lập phiếu rồi lùi ngày mượn/hạn trả để mô phỏng trễ hạn (thay cho chỉnh due_date trong DB). */
    UUID borrowedCopyDueDaysAgo(UUID reader, int daysLate, String price) {
        UUID copyId = copy(CopyStatus.AVAILABLE, price);
        BorrowingRecord r = borrow.execute(new BorrowBookCommand(reader, List.of(copyId))) == null ? null
                : repository.findOpenByCopyIdForUpdate(copyId).orElseThrow();
        BorrowingRecord late = BorrowingRecord.restore(r.id(), r.userId(), r.items(), r.borrowDate().minusDays(60),
                TODAY.minusDays(daysLate), 0, r.loanDays());
        records.put(r.id().value(), late);
        return copyId;
    }

    // ---- UC06 ----

    @Test
    void sinhVienMuon5CuonDuocHanTra14NgayVaBanSaoLoaned() {
        List<UUID> ids = available(5);

        BorrowingResponse response = borrow.execute(new BorrowBookCommand(student, ids));

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.dueDate()).isEqualTo(TODAY.plusDays(14));
        assertThat(ids).allMatch(id -> copies.get(id).status() == CopyStatus.LOANED);
        assertThat(events).hasSize(1);
    }

    @Test
    void sinhVienMuonCuonThu6BiTuChoi() {
        borrow.execute(new BorrowBookCommand(student, available(5)));
        UUID sixth = copy(CopyStatus.AVAILABLE, "100000");

        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, List.of(sixth))))
                .isInstanceOf(BorrowingLimitExceededException.class).hasMessageContaining("còn được mượn 0 cuốn");
        assertThat(copies.get(sixth).status()).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    void sinhVienMuon6CuonTrongMotPhieuBiTuChoi() {
        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, available(6))))
                .isInstanceOf(BorrowingLimitExceededException.class);
        assertThat(records).isEmpty();
    }

    @Test
    void giangVienMuonToi10CuonTrong30Ngay() {
        BorrowingResponse response = borrow.execute(new BorrowBookCommand(lecturer, available(10)));

        assertThat(response.items()).hasSize(10);
        assertThat(response.dueDate()).isEqualTo(TODAY.plusDays(30));
        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(lecturer, available(1))))
                .isInstanceOf(BorrowingLimitExceededException.class);
    }

    @Test
    void theHetHanHoacBiKhoaBiTuChoiQD2() {
        readers.put(student, new ReaderDirectoryPort.ReaderInfo(student, ReaderType.STUDENT, false));

        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, available(1))))
                .isInstanceOf(BorrowingRejectedException.class)
                .extracting(e -> ((BorrowingRejectedException) e).code()).isEqualTo("CARD_INVALID");
    }

    @Test
    void khongCoHoSoDocGiaBaoKhongTimThay() {
        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(UUID.randomUUID(), available(1))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void giuSachQuaHanBiTuChoiQD2() {
        borrowedCopyDueDaysAgo(student, 3, "100000");

        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, available(1))))
                .isInstanceOf(BorrowingRejectedException.class)
                .extracting(e -> ((BorrowingRejectedException) e).code()).isEqualTo("OVERDUE_ITEMS");
    }

    @Test
    void noPhatBiTuChoiVaThuPhatXongMuonLaiDuocQD2UC09() {
        UUID copyId = borrowedCopyDueDaysAgo(student, 3, "100000");
        giveBack.execute(new ReturnBookCommand(null, copyId, null, ItemCondition.GOOD)); // trễ 3 ngày: 15.000đ

        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, available(1))))
                .isInstanceOf(BorrowingRejectedException.class)
                .extracting(e -> ((BorrowingRejectedException) e).code()).isEqualTo("UNPAID_FINES");

        var collected = collectFines.execute(student);
        assertThat(collected.collectedCount()).isEqualTo(1);
        assertThat(collected.totalCollected()).isEqualByComparingTo("15000");
        assertThat(collectFines.execute(student).collectedCount()).isZero(); // không thu trùng

        assertThat(borrow.execute(new BorrowBookCommand(student, available(1))).status()).isEqualTo("ACTIVE");
    }

    @Test
    void banSaoDangMuonMatHoacHongBiTuChoi() {
        for (CopyStatus status : List.of(CopyStatus.LOANED, CopyStatus.LOST, CopyStatus.DAMAGED)) {
            UUID id = copy(status, "100000");
            assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, List.of(id))))
                    .isInstanceOf(BorrowingRejectedException.class)
                    .extracting(e -> ((BorrowingRejectedException) e).code()).isEqualTo("COPY_NOT_AVAILABLE");
        }
        assertThat(records).isEmpty();
    }

    @Test
    void banSaoGiuChoNguoiKhacBiTuChoiConGiuChoChinhMinhThiNhanVaDatTruocFulfilled() {
        UUID held = copy(CopyStatus.RESERVED, "100000");
        holders.put(held, lecturer);
        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, List.of(held))))
                .isInstanceOf(BorrowingRejectedException.class)
                .extracting(e -> ((BorrowingRejectedException) e).code()).isEqualTo("COPY_RESERVED_FOR_OTHER");

        BorrowingResponse response = borrow.execute(new BorrowBookCommand(lecturer, List.of(held)));

        assertThat(response.items()).hasSize(1);
        assertThat(fulfilled).containsExactly(held + ":" + lecturer);
        assertThat(copies.get(held).status()).isEqualTo(CopyStatus.LOANED);
    }

    @Test
    void catalogLoiGiuaChungThiHoanTacCacBanSaoDaDoi() {
        List<UUID> ids = available(3);
        failStatusChangeFor = ids.get(2);

        assertThatThrownBy(() -> borrow.execute(new BorrowBookCommand(student, ids)))
                .isInstanceOf(DependencyUnavailableException.class);

        assertThat(ids).allMatch(id -> copies.get(id).status() == CopyStatus.AVAILABLE);
        assertThat(events).isEmpty();
    }

    // ---- UC07 ----

    @Test
    void traTre3NgayPhat15000VaBanSaoVeAvailable() {
        UUID copyId = borrowedCopyDueDaysAgo(student, 3, "100000");

        BorrowingResponse response = giveBack.execute(new ReturnBookCommand(null, copyId, null, ItemCondition.GOOD));

        assertThat(response.status()).isEqualTo("RETURNED");
        assertThat(response.fines()).hasSize(1);
        assertThat(response.fines().get(0).reason()).isEqualTo("LATE_RETURN");
        assertThat(response.fines().get(0).amount()).isEqualByComparingTo("15000");
        assertThat(copies.get(copyId).status()).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    void traDungHanKhongPhat() {
        UUID copyId = available(1).get(0);
        borrow.execute(new BorrowBookCommand(student, List.of(copyId)));

        BorrowingResponse response = giveBack.execute(new ReturnBookCommand(null, copyId, null, null));

        assertThat(response.fines()).isEmpty();
        assertThat(fines).isEmpty();
    }

    @Test
    void honVaTre2NgayGiaBia200000PhatCaHai100000Va10000() {
        UUID copyId = borrowedCopyDueDaysAgo(student, 2, "200000");

        BorrowingResponse response = giveBack.execute(new ReturnBookCommand(null, null, copies.get(copyId).barcode(),
                ItemCondition.DAMAGED));

        assertThat(response.fines()).extracting(FineResponse::reason).containsExactlyInAnyOrder("LATE_RETURN", "DAMAGED");
        assertThat(response.fines().stream().map(FineResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("110000");
        assertThat(copies.get(copyId).status()).isEqualTo(CopyStatus.DAMAGED);
    }

    @Test
    void traBanSaoKhongCoLuotMuonDangMoBaoLoi() {
        UUID copyId = available(1).get(0);

        assertThatThrownBy(() -> giveBack.execute(new ReturnBookCommand(null, copyId, null, ItemCondition.GOOD)))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("COPY_NOT_ON_LOAN");
    }

    @Test
    void traMotPhanPhieuVanActiveTraHetMoiReturned() {
        List<UUID> ids = available(2);
        borrow.execute(new BorrowBookCommand(student, ids));

        assertThat(giveBack.execute(new ReturnBookCommand(null, ids.get(0), null, ItemCondition.GOOD)).status()).isEqualTo("ACTIVE");
        assertThat(giveBack.execute(new ReturnBookCommand(null, ids.get(1), null, ItemCondition.GOOD)).status()).isEqualTo("RETURNED");
    }

    // ---- UC08 ----

    @Test
    void baoMatGiaBia100000BoiThuong150000KhongTinhPhiTreDuDaQuaHan() {
        UUID copyId = borrowedCopyDueDaysAgo(student, 10, "100000");

        BorrowingResponse response = lose.execute(new ReportLostCommand(copyId, null));

        assertThat(response.status()).isEqualTo("RETURNED");
        assertThat(response.items().get(0).condition()).isEqualTo("LOST");
        assertThat(response.fines()).hasSize(1);
        assertThat(response.fines().get(0).reason()).isEqualTo("LOST");
        assertThat(response.fines().get(0).amount()).isEqualByComparingTo("150000");
        assertThat(copies.get(copyId).status()).isEqualTo(CopyStatus.LOST);
        assertThat(fines).noneMatch(f -> f.reason() == FineReason.LATE_RETURN);
        assertThat(fines).allMatch(f -> f.status() == FineStatus.UNPAID);
    }

    @Test
    void baoMatBanSaoKhongMuonBaoLoi() {
        UUID copyId = available(1).get(0);

        assertThatThrownBy(() -> lose.execute(new ReportLostCommand(copyId, null))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void lapPhatIdempotentTheoLuotMuonBanSaoVaLyDo() {
        UUID copyId = borrowedCopyDueDaysAgo(student, 3, "100000");
        BorrowingRecord record = repository.findOpenByCopyIdForUpdate(copyId).orElseThrow();
        AssessFineCommand command = new AssessFineCommand(student, record.id().value(), copyId, new BigDecimal("100000"),
                3, AssessFineCommand.Outcome.RETURNED_GOOD);

        assertThat(assessFine.execute(command)).hasSize(1);
        assertThat(assessFine.execute(command)).isEmpty();
        assertThat(fines).hasSize(1);
        assertThat(Instant.now()).isNotNull();
    }
}
