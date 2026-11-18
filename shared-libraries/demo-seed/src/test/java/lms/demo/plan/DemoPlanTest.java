package lms.demo.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lms.demo.plan.DemoPlan.CopyStatus;
import lms.demo.plan.DemoPlan.OrderStatus;
import lms.demo.plan.DemoPlan.PaymentStatus;
import lms.demo.plan.DemoPlan.RefundStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DemoPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final DemoPlan plan = DemoPlan.generate(TODAY, DemoPlan.DEFAULT_SEED);

    @Test
    void demIdVaSoLuongToiThieu() {
        assertThat(plan.titles()).hasSizeGreaterThanOrEqualTo(60);
        assertThat(plan.copies()).hasSizeGreaterThanOrEqualTo(150);
        assertThat(plan.readers()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(plan.staff()).hasSize(3);
        assertThat(plan.borrowings()).hasSizeGreaterThanOrEqualTo(120);
        assertThat(plan.stockItems()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(plan.orders()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(plan.suppliers()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(plan.receipts()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(plan.salesReturns()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void dungSauPhieuQuaHan() {
        assertThat(plan.overdueBorrowings()).hasSize(6);
    }

    @Test
    void phieuDenHanDungHomNayChuaQuaHan() {
        assertThat(plan.borrowings()).anyMatch(b -> b.isActive() && b.dueDate().equals(TODAY));
        assertThat(plan.borrowings().stream().filter(b -> b.isActive() && b.dueDate().equals(TODAY)))
                .noneMatch(b -> b.isOverdue(TODAY));
        assertThat(plan.borrowings()).anyMatch(b -> b.isActive() && b.dueDate().equals(TODAY.minusDays(1)));
    }

    @Test
    void coItNhatMuoiPhieuActiveChuaQuaHan() {
        long openNotOverdue = plan.borrowings().stream().filter(b -> b.isActive() && !b.isOverdue(TODAY)).count();
        assertThat(openNotOverdue).isGreaterThanOrEqualTo(10);
    }

    @Test
    void dungBonPhatChuaThuTongTienCoDinh() {
        assertThat(plan.unpaidFines()).hasSize(4);
        assertThat(plan.unpaidFinesTotal()).isEqualTo(DemoPlan.UNPAID_FINES_TOTAL);
        assertThat(plan.fines().stream().filter(f -> f.status() == DemoPlan.FineStatus.PAID).count())
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void dungNamMatHangSapHetCoCaHetTon() {
        assertThat(plan.lowStockItems()).hasSize(5);
        assertThat(plan.lowStockItems()).anyMatch(s -> s.available() == 0);
        // biên: ngưỡng riêng thắng ngưỡng chung
        assertThat(plan.stockItems().get(5).isLowStock(DemoPlan.GENERAL_LOW_STOCK_THRESHOLD)).isFalse();
        assertThat(plan.stockItems().get(3).isLowStock(DemoPlan.GENERAL_LOW_STOCK_THRESHOLD)).isTrue();
    }

    @Test
    void banSaoDuNamTrangThaiVaLoanedKhopPhieuDangMo() {
        Map<CopyStatus, Long> byStatus = plan.copies().stream()
                .collect(Collectors.groupingBy(DemoPlan.CopyPlan::status, Collectors.counting()));
        for (CopyStatus s : CopyStatus.values()) {
            assertThat(byStatus.getOrDefault(s, 0L)).as(s.name()).isGreaterThan(0);
        }
        Set<UUID> openCopies = plan.borrowings().stream().flatMap(b -> b.items().stream())
                .filter(i -> !i.isReturned()).map(DemoPlan.LoanItemPlan::copyId).collect(Collectors.toSet());
        Set<UUID> loaned = plan.copies().stream().filter(c -> c.status() == CopyStatus.LOANED)
                .map(DemoPlan.CopyPlan::id).collect(Collectors.toSet());
        assertThat(openCopies).isEqualTo(loaned);
        long openItems = plan.borrowings().stream().flatMap(b -> b.items().stream()).filter(i -> !i.isReturned()).count();
        assertThat(openItems).isEqualTo(openCopies.size());
    }

    @Test
    void banSaoReservedKhopDatTruoc() {
        Set<UUID> reserved = plan.copies().stream().filter(c -> c.status() == CopyStatus.RESERVED)
                .map(DemoPlan.CopyPlan::id).collect(Collectors.toSet());
        assertThat(plan.reservations().stream().map(DemoPlan.ReservationPlan::copyId).collect(Collectors.toSet()))
                .isEqualTo(reserved);
    }

    @Test
    void moiDauSachCoItNhatMotBanSaoVaThamChieuHopLe() {
        Set<UUID> titleIds = plan.titles().stream().map(DemoPlan.TitlePlan::id).collect(Collectors.toSet());
        assertThat(plan.copies().stream().map(DemoPlan.CopyPlan::titleId).collect(Collectors.toSet())).isEqualTo(titleIds);
        Set<UUID> copyIds = plan.copies().stream().map(DemoPlan.CopyPlan::id).collect(Collectors.toSet());
        Set<UUID> readerIds = plan.readers().stream().map(DemoPlan.ReaderPlan::id).collect(Collectors.toSet());
        for (DemoPlan.BorrowingPlan b : plan.borrowings()) {
            assertThat(readerIds).contains(b.readerId());
            assertThat(b.items()).isNotEmpty();
            b.items().forEach(i -> assertThat(copyIds).contains(i.copyId()));
        }
    }

    @Test
    void ngayLichSuHopLe() {
        for (DemoPlan.BorrowingPlan b : plan.borrowings()) {
            assertThat(b.borrowDaysAgo()).isBetween(0, 59);
            assertThat(b.borrowDate()).isEqualTo(TODAY.minusDays(b.borrowDaysAgo()));
            assertThat(b.dueDate()).isEqualTo(b.borrowDate().plusDays(DemoPlan.LOAN_DAYS));
            for (DemoPlan.LoanItemPlan item : b.items()) {
                if (item.isReturned()) {
                    assertThat(item.returnDate()).isBetween(b.borrowDate(), TODAY);
                }
            }
        }
    }

    @Test
    void khongCoHaiPhieuMoCungMotBanSao() {
        List<UUID> open = plan.borrowings().stream().flatMap(b -> b.items().stream())
                .filter(i -> !i.isReturned()).map(DemoPlan.LoanItemPlan::copyId).toList();
        assertThat(new HashSet<>(open)).hasSameSizeAs(open);
    }

    @Test
    void khongTrungKhoaTuNhien() {
        assertThat(plan.titles().stream().map(DemoPlan.TitlePlan::isbn)).doesNotHaveDuplicates();
        assertThat(plan.copies().stream().map(DemoPlan.CopyPlan::barcode)).doesNotHaveDuplicates();
        assertThat(plan.orders().stream().map(DemoPlan.OrderPlan::orderNo)).doesNotHaveDuplicates();
        assertThat(plan.readers().stream().map(DemoPlan.ReaderPlan::username)).doesNotHaveDuplicates();
        assertThat(plan.orders()).allMatch(o -> o.orderNo().startsWith(DemoPlan.ORDER_PREFIX));
        assertThat(plan.readers()).allMatch(r -> r.email().endsWith("@" + DemoPlan.EMAIL_DOMAIN));
    }

    @Test
    void dauSachCoThongTinBoSungHopLe() {
        assertThat(plan.titles()).hasSize(64);
        assertThat(plan.titles().stream().map(DemoPlan.TitlePlan::publisher)).doesNotContainNull();
        for (DemoPlan.TitlePlan t : plan.titles()) {
            assertThat(t.publishYear()).isBetween(2005, 2024);
            assertThat(t.pageCount()).isBetween(120, 700);
            assertThat(t.language()).isIn("vi", "en");
            assertThat(t.format()).isIn("PAPERBACK", "HARDCOVER", "EBOOK");
            assertThat(t.tags()).isNotEmpty().hasSizeLessThanOrEqualTo(10).doesNotHaveDuplicates();
            t.tags().forEach(tag -> assertThat(tag).isNotBlank().hasSizeLessThanOrEqualTo(50));
            assertThat(t.seriesNumber() == null || t.series() != null).isTrue();
            assertThat(t.coverImageUrl() == null || t.coverImageUrl().startsWith("https://")).isTrue();
            assertThat(t.name().length()).isLessThanOrEqualTo(255);
        }
        // nhóm theo chủ đề: mỗi chủ đề 8 sách liên tiếp
        for (int i = 0; i < 64; i += 8) {
            String category = plan.titles().get(i).category();
            for (int j = i; j < i + 8; j++) assertThat(plan.titles().get(j).category()).isEqualTo(category);
        }
        // sách cùng bộ có số tập khác nhau
        var bySeries = plan.titles().stream().filter(t -> t.series() != null)
                .collect(Collectors.groupingBy(DemoPlan.TitlePlan::series));
        assertThat(bySeries).isNotEmpty();
        bySeries.values().forEach(group -> {
            assertThat(group.stream().map(DemoPlan.TitlePlan::seriesNumber)).doesNotContainNull().doesNotHaveDuplicates();
        });
    }

    @Test
    void banSaoCoThongTinNhapKhoHopLe() {
        assertThat(plan.copies()).hasSize(160);
        java.util.Set<String> sources = new HashSet<>();
        java.util.Set<String> grades = new HashSet<>();
        for (DemoPlan.CopyPlan c : plan.copies()) {
            assertThat(c.acquiredDate()).isNotNull().isBefore(TODAY);
            assertThat(c.lastInventoryDate()).isNotNull().isAfterOrEqualTo(c.acquiredDate()).isBeforeOrEqualTo(TODAY);
            assertThat(c.acquisitionSource()).isIn("PURCHASE", "DONATION", "EXCHANGE", "OTHER");
            sources.add(c.acquisitionSource());
            if (c.conditionGrade() != null) {
                assertThat(c.conditionGrade()).isIn("NEW", "GOOD", "FAIR", "WORN");
                grades.add(c.conditionGrade());
            }
            if (c.acquisitionSource().equals("PURCHASE")) {
                assertThat(c.purchasePrice()).isNotNull().isPositive();
            } else {
                assertThat(c.purchasePrice()).isNull();
            }
            assertThat(c.conditionNote() == null || c.conditionNote().length() <= 300).isTrue();
            assertThat(c.shelfIndex()).isBetween(0, plan.shelves().size() - 1);
        }
        assertThat(sources).hasSize(4);
        assertThat(grades).hasSize(4);
    }

    @Test
    void tacGiaNhaXuatBanVaKeKhopDauSach() {
        Set<String> authorNames = plan.authors().stream().map(DemoPlan.AuthorPlan::name).collect(Collectors.toSet());
        Set<String> publisherNames = plan.publishers().stream().map(DemoPlan.PublisherPlan::name)
                .collect(Collectors.toSet());
        assertThat(authorNames).hasSameSizeAs(plan.authors());
        assertThat(publisherNames).hasSameSizeAs(plan.publishers());
        for (DemoPlan.TitlePlan t : plan.titles()) {
            assertThat(authorNames).contains(t.author());
            assertThat(publisherNames).contains(t.publisher());
        }
        for (DemoPlan.AuthorPlan a : plan.authors()) {
            assertThat(a.birthYear() == null || a.birthYear() >= 1).isTrue();
            assertThat(a.deathYear() == null || a.birthYear() == null || a.deathYear() >= a.birthYear()).isTrue();
            assertThat(a.website() == null || a.website().startsWith("http")).isTrue();
        }
        for (DemoPlan.PublisherPlan p : plan.publishers()) {
            assertThat(p.foundedYear() == null || p.foundedYear() >= 1400).isTrue();
            assertThat(p.website() == null || p.website().startsWith("http")).isTrue();
            assertThat(p.country()).isNotBlank();
        }
        assertThat(plan.shelves()).hasSize(16);
        assertThat(plan.shelves().stream().map(DemoPlan.ShelfPlan::code)).doesNotHaveDuplicates();
        plan.shelves().forEach(s -> assertThat(s.capacity()).isBetween(1, 100000));
    }

    @Test
    void isbnCoChuSoKiemTraDung() {
        for (DemoPlan.TitlePlan t : plan.titles()) {
            assertThat(t.isbn()).hasSize(13).containsOnlyDigits();
            int sum = 0;
            for (int i = 0; i < 13; i++) {
                int d = t.isbn().charAt(i) - '0';
                sum += i % 2 == 0 ? d : d * 3;
            }
            assertThat(sum % 10).isZero();
        }
    }

    @Test
    void donHangPhanBoVaTongTien() {
        assertThat(plan.orders().stream().filter(o -> o.isPaid() && o.createdDaysAgo() == 0).count()).isGreaterThanOrEqualTo(2);
        assertThat(plan.orders().stream().map(DemoPlan.OrderPlan::channel).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(DemoPlan.Channel.POS, DemoPlan.Channel.ONLINE);
        assertThat(plan.orders().stream().map(DemoPlan.OrderPlan::status).collect(Collectors.toSet()))
                .contains(OrderStatus.CANCELLED, OrderStatus.COMPLETED, OrderStatus.PENDING_CONFIRMATION);
        assertThat(plan.orders().stream().map(DemoPlan.OrderPlan::paymentStatus).collect(Collectors.toSet()))
                .contains(PaymentStatus.PAID, PaymentStatus.UNPAID);
        for (DemoPlan.OrderPlan o : plan.orders()) {
            assertThat(o.createdDaysAgo()).isBetween(0, 29);
            assertThat(o.minuteOfDay()).isBetween(0, 1439);
            long subtotal = o.lines().stream().mapToLong(DemoPlan.OrderLinePlan::lineTotal).sum();
            assertThat(o.subtotal()).isEqualTo(subtotal);
            assertThat(o.total()).isEqualTo(subtotal - o.discount() + o.vat() + o.shippingFee());
            assertThat(o.lines().stream().map(DemoPlan.OrderLinePlan::titleId)).doesNotHaveDuplicates();
        }
    }

    @Test
    void hoanTienKhongVuotTongDonVaCoDuTrangThai() {
        assertThat(plan.refunds().stream().map(DemoPlan.RefundPlan::status).collect(Collectors.toSet()))
                .contains(RefundStatus.REFUNDED, RefundStatus.PENDING);
        Map<Integer, Long> refunded = plan.refunds().stream().filter(r -> r.status() == RefundStatus.REFUNDED)
                .collect(Collectors.groupingBy(DemoPlan.RefundPlan::orderIndex,
                        Collectors.summingLong(DemoPlan.RefundPlan::amount)));
        refunded.forEach((orderIndex, sum) ->
                assertThat(sum).isLessThanOrEqualTo(plan.orders().get(orderIndex).total()));
        for (DemoPlan.SalesReturnPlan r : plan.salesReturns()) {
            DemoPlan.OrderPlan order = plan.orders().get(r.orderIndex());
            assertThat(order.isPaid()).isTrue();
            assertThat(order.lines()).anyMatch(l -> l.titleId().equals(r.titleId()) && l.quantity() >= r.quantity());
            assertThat(r.createdDaysAgo()).isLessThanOrEqualTo(order.createdDaysAgo());
        }
    }

    @Test
    void soCaiTonKhopOnHandVaCoPhieuNhapXacNhan() {
        for (DemoPlan.StockItemPlan s : plan.stockItems()) {
            assertThat(s.ledgerTotal()).as("item %d", s.index()).isEqualTo(s.onHand());
            assertThat(s.receivedQuantity()).isPositive();
            assertThat(s.reserved()).isBetween(0, s.onHand());
        }
        Map<UUID, Integer> receipted = plan.receipts().stream().flatMap(r -> r.lines().stream())
                .collect(Collectors.toMap(DemoPlan.ReceiptLinePlan::titleId, DemoPlan.ReceiptLinePlan::quantity));
        for (DemoPlan.StockItemPlan s : plan.stockItems()) {
            assertThat(receipted.get(s.titleId())).isEqualTo(s.receivedQuantity());
        }
    }

    @Test
    void tatDinhCungNgayVaHatGiongThiGiongHet() {
        assertThat(DemoPlan.generate(TODAY, DemoPlan.DEFAULT_SEED)).isEqualTo(plan);
    }

    @Test
    void hatGiongKhacThiKhacKeHoach() {
        assertThat(DemoPlan.generate(TODAY, DemoPlan.DEFAULT_SEED + 1)).isNotEqualTo(plan);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-18", "2026-11-23", "2027-01-01", "2026-02-28"})
    void batBienGiuNguyenKhiDoiNgayChay(String date) {
        LocalDate other = LocalDate.parse(date);
        DemoPlan shifted = DemoPlan.generate(other, DemoPlan.DEFAULT_SEED);

        assertThat(shifted.overdueBorrowings()).hasSize(6);
        assertThat(shifted.unpaidFines()).hasSize(4);
        assertThat(shifted.unpaidFinesTotal()).isEqualTo(DemoPlan.UNPAID_FINES_TOTAL);
        assertThat(shifted.lowStockItems()).hasSize(5);

        // cấu trúc: id, người mượn, bản sao mở, trạng thái bản sao
        Function<DemoPlan, List<UUID>> ids = p -> p.borrowings().stream().map(DemoPlan.BorrowingPlan::id).toList();
        assertThat(ids.apply(shifted)).isEqualTo(ids.apply(plan));
        assertThat(shifted.borrowings().stream().map(DemoPlan.BorrowingPlan::readerId).toList())
                .isEqualTo(plan.borrowings().stream().map(DemoPlan.BorrowingPlan::readerId).toList());
        assertThat(shifted.copies()).isEqualTo(plan.copies());
        assertThat(shifted.titles()).isEqualTo(plan.titles());
        assertThat(shifted.staff()).isEqualTo(plan.staff());
        assertThat(shifted.readers()).isEqualTo(plan.readers());
        assertThat(shifted.reservations()).isEqualTo(plan.reservations());
        assertThat(shifted.stockItems()).isEqualTo(plan.stockItems());
        assertThat(shifted.orders()).isEqualTo(plan.orders());
        assertThat(shifted.fines().stream().map(DemoPlan.FinePlan::id).toList())
                .isEqualTo(plan.fines().stream().map(DemoPlan.FinePlan::id).toList());
        for (int i = 0; i < plan.borrowings().size(); i++) {
            List<UUID> openA = plan.borrowings().get(i).items().stream().filter(x -> !x.isReturned())
                    .map(DemoPlan.LoanItemPlan::copyId).toList();
            List<UUID> openB = shifted.borrowings().get(i).items().stream().filter(x -> !x.isReturned())
                    .map(DemoPlan.LoanItemPlan::copyId).toList();
            assertThat(openB).isEqualTo(openA);
        }
        long overdueA = plan.overdueBorrowings().stream().map(DemoPlan.BorrowingPlan::id).count();
        assertThat(shifted.overdueBorrowings().stream().map(DemoPlan.BorrowingPlan::id).toList())
                .isEqualTo(plan.overdueBorrowings().stream().map(DemoPlan.BorrowingPlan::id).toList())
                .hasSize((int) overdueA);
    }

    @Test
    void demoIdsTatDinhVaPhanBietTheoLoai() {
        assertThat(DemoIds.title(3)).isEqualTo(DemoIds.title(3));
        assertThat(DemoIds.title(3)).isNotEqualTo(DemoIds.copy(3));
        assertThat(DemoIds.title(3)).isNotEqualTo(DemoIds.title(4));
        assertThat(DemoIds.of("title", 3)).isEqualTo(DemoIds.title(3));
        assertThat(DemoIds.tenant()).isEqualTo(DemoIds.of("tenant", 0));
    }

    @Test
    void tenKhongRong() {
        assertThat(plan.titles()).allMatch(t -> !t.name().isBlank() && !t.author().isBlank());
        assertThat(plan.titles().stream().map(DemoPlan.TitlePlan::name)).doesNotHaveDuplicates();
    }
}
