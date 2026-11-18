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

    /** 64 ấn bản gốc + ấn bản bổ sung của mô hình tác phẩm (ADR-0026). */
    private static final int BASE_TITLES = 64;
    private static final int TOTAL_TITLES = BASE_TITLES + DemoTitleData.extras().size();

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
        assertThat(plan.titles()).hasSize(TOTAL_TITLES);
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
        for (int i = 0; i < BASE_TITLES; i += 8) {
            String category = plan.titles().get(i).category();
            for (int j = i; j < i + 8; j++) assertThat(plan.titles().get(j).category()).isEqualTo(category);
        }
        // tác phẩm cùng bộ có số tập khác nhau (nhiều ấn bản của một tập thì cùng số tập; bộ hộp không có số tập)
        var bySeries = plan.works().stream().filter(w -> w.seriesIndex() != null && w.kind() == DemoPlan.WorkKind.SINGLE)
                .collect(Collectors.groupingBy(DemoPlan.WorkPlan::seriesIndex));
        assertThat(bySeries).isNotEmpty();
        bySeries.values().forEach(group -> {
            assertThat(group.stream().map(DemoPlan.WorkPlan::seriesNumber)).doesNotContainNull().doesNotHaveDuplicates();
        });
    }

    @Test
    void banSaoCoThongTinNhapKhoHopLe() {
        assertThat(plan.copies()).hasSize(160 + DemoTitleData.extras().size());
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
        // ấn bản của cùng tác phẩm có thể trùng tên, nhưng phải phân biệt được (phụ đề, định dạng, ngôn ngữ, lần tái bản)
        assertThat(plan.titles().stream().map(t -> t.name() + "|" + t.subtitle() + "|" + t.format() + "|" + t.language()
                + "|" + t.edition())).doesNotHaveDuplicates();
    }

    // ---- Mô hình tác phẩm - ấn bản - bản sao (ADR-0026) ----

    @Test
    void sauMuoiBonAnBanGocGiuNguyenIdVaLaTacPhamMotMot() {
        for (int i = 0; i < BASE_TITLES; i++) {
            DemoPlan.TitlePlan t = plan.titles().get(i);
            assertThat(t.index()).isEqualTo(i);
            assertThat(t.id()).isEqualTo(DemoIds.title(i));
            assertThat(t.workId()).isEqualTo(DemoIds.legacyWork(t.id()));
        }
        // id ấn bản gốc không đổi so với trước khi có mô hình tác phẩm: nameUUIDFromBytes("lms-demo:title:<n>")
        assertThat(plan.titles().get(0).id()).isEqualTo(UUID.nameUUIDFromBytes("lms-demo:title:0".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void idTacPhamMotMotKhopCongThucBackfillMd5CuaMigration() throws Exception {
        UUID titleId = DemoIds.title(5);
        byte[] d = java.security.MessageDigest.getInstance("MD5").digest(
                ("lms-work:" + titleId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02x", b));
        // md5(...)::uuid của Postgres: 32 ký tự hex chia 8-4-4-4-12, không chỉnh bit phiên bản
        UUID expected = UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
        assertThat(DemoIds.legacyWork(titleId)).isEqualTo(expected);
        assertThat(DemoIds.legacyWork(titleId)).isNotEqualTo(UUID.nameUUIDFromBytes(
                ("lms-work:" + titleId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void moiAnBanThuocDungMotTacPhamVaMoiTacPhamCoItNhatMotAnBan() {
        Map<UUID, DemoPlan.WorkPlan> works = plan.works().stream()
                .collect(Collectors.toMap(DemoPlan.WorkPlan::id, Function.identity()));
        assertThat(works).hasSameSizeAs(plan.works());
        for (DemoPlan.TitlePlan t : plan.titles()) {
            assertThat(works).containsKey(t.workId());
            assertThat(works.get(t.workId()).editionIds()).contains(t.id());
        }
        for (DemoPlan.WorkPlan w : plan.works()) {
            assertThat(w.editionIds()).isNotEmpty().doesNotHaveDuplicates();
            assertThat(w.title()).isNotBlank();
            assertThat(w.author()).isNotBlank();
            assertThat(w.originalLanguage() == null || w.originalLanguage().matches("[a-z]{2,8}")).isTrue();
            assertThat(w.seriesNumber() == null || w.seriesIndex() != null).isTrue();
            if (w.seriesIndex() != null) assertThat(w.seriesIndex()).isBetween(0, plan.series().size() - 1);
        }
        assertThat(plan.works().stream().flatMap(w -> w.editionIds().stream())).hasSameSizeAs(plan.titles());
    }

    @Test
    void coBoSachNhieuTapNhieuAnBanTapCoPhanVaBoHop() {
        // vài bộ nhiều tập, mỗi tập một tác phẩm riêng có số tập
        assertThat(plan.series()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(plan.series().stream().map(DemoPlan.SeriesPlan::name)).doesNotHaveDuplicates();
        assertThat(plan.series().stream().map(DemoPlan.SeriesPlan::id)).doesNotHaveDuplicates();
        long multiVolume = plan.series().stream().filter(s -> plan.works().stream().filter(w ->
                w.kind() == DemoPlan.WorkKind.SINGLE && w.seriesIndex() != null && w.seriesIndex() == s.index())
                .count() >= 2).count();
        assertThat(multiVolume).isGreaterThanOrEqualTo(3);

        // có tác phẩm nhiều ấn bản: khác định dạng và khác ngôn ngữ
        List<DemoPlan.WorkPlan> multiEdition = plan.works().stream().filter(w -> w.editionIds().size() >= 2).toList();
        assertThat(multiEdition).hasSizeGreaterThanOrEqualTo(4);
        Map<UUID, DemoPlan.TitlePlan> titleById = plan.titles().stream()
                .collect(Collectors.toMap(DemoPlan.TitlePlan::id, Function.identity()));
        Set<String> formats = new HashSet<>();
        Set<String> languages = new HashSet<>();
        for (DemoPlan.WorkPlan w : multiEdition) {
            w.editionIds().forEach(id -> {
                formats.add(titleById.get(id).format());
                languages.add(titleById.get(id).language());
            });
        }
        assertThat(formats).contains("PAPERBACK", "HARDCOVER", "EBOOK");
        assertThat(languages).contains("vi", "en");

        // tập chia phần
        List<DemoPlan.TitlePlan> parts = plan.titles().stream().filter(t -> t.partNumber() != null).toList();
        assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(parts.stream().map(DemoPlan.TitlePlan::partNumber)).containsExactlyInAnyOrder(1, 2);
        assertThat(parts.stream().map(DemoPlan.TitlePlan::workId).distinct()).hasSize(1);
        assertThat(parts).allMatch(t -> t.partLabel() != null && !t.partLabel().isBlank());

        // bộ hộp
        List<DemoPlan.WorkPlan> boxed = plan.works().stream().filter(w -> w.kind() == DemoPlan.WorkKind.COLLECTION).toList();
        assertThat(boxed).hasSize(1);
        assertThat(boxed.get(0).seriesIndex()).isNotNull();
        assertThat(boxed.get(0).seriesNumber()).isNull();
        assertThat(boxed.get(0).id()).isEqualTo(DemoIds.work(0));
    }

    @Test
    void anBanBoSungCoBanSaoVaMatHangTonRiengKhopTacPham() {
        Set<UUID> copyTitles = plan.copies().stream().map(DemoPlan.CopyPlan::titleId).collect(Collectors.toSet());
        Map<UUID, DemoPlan.StockItemPlan> stock = plan.stockItems().stream()
                .collect(Collectors.toMap(DemoPlan.StockItemPlan::titleId, Function.identity()));
        for (DemoPlan.TitlePlan t : plan.titles().subList(BASE_TITLES, plan.titles().size())) {
            assertThat(copyTitles).contains(t.id());
            assertThat(stock).containsKey(t.id());
        }
        // snapshot work_id của sales khớp tác phẩm của ấn bản
        Map<UUID, UUID> workOf = plan.titles().stream()
                .collect(Collectors.toMap(DemoPlan.TitlePlan::id, DemoPlan.TitlePlan::workId));
        plan.stockItems().forEach(s -> assertThat(s.workId()).isEqualTo(workOf.get(s.titleId())));
        // bản sao của ấn bản bổ sung đều rảnh và không dính phiếu mượn
        Set<UUID> borrowed = plan.borrowings().stream().flatMap(b -> b.items().stream())
                .map(DemoPlan.LoanItemPlan::copyId).collect(Collectors.toSet());
        plan.copies().stream().filter(c -> c.index() >= 160).forEach(c -> {
            assertThat(c.status()).isEqualTo(CopyStatus.AVAILABLE);
            assertThat(borrowed).doesNotContain(c.id());
        });
    }

    @Test
    void datTruocCoTacPhamVaNgonNguVaDatTruocTheoTacPhamHopLe() {
        Map<UUID, DemoPlan.TitlePlan> titleById = plan.titles().stream()
                .collect(Collectors.toMap(DemoPlan.TitlePlan::id, Function.identity()));
        plan.reservations().forEach(r -> {
            assertThat(r.workId()).isEqualTo(titleById.get(r.titleId()).workId());
            assertThat(r.language()).isEqualTo(titleById.get(r.titleId()).language());
        });
        assertThat(plan.workReservations()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(plan.workReservations().stream().map(DemoPlan.WorkReservationPlan::language)).contains("vi", "en");
        assertThat(plan.workReservations().stream().map(DemoPlan.WorkReservationPlan::language)).containsNull();
        Set<UUID> readerIds = plan.readers().stream().map(DemoPlan.ReaderPlan::id).collect(Collectors.toSet());
        Set<String> active = new HashSet<>();
        plan.reservations().forEach(r -> assertThat(active.add(r.readerId() + "|" + r.workId())).isTrue());
        for (DemoPlan.WorkReservationPlan w : plan.workReservations()) {
            assertThat(readerIds).contains(w.readerId());
            assertThat(w.workId()).isEqualTo(titleById.get(w.titleId()).workId());
            assertThat(w.createdDaysAgo()).isBetween(0, 7);
            // uq_reservations_active_work: một độc giả một đặt trước còn hiệu lực trên một tác phẩm
            assertThat(active.add(w.readerId() + "|" + w.workId())).as("trùng độc giả/tác phẩm %d", w.index()).isTrue();
        }
        assertThat(plan.workReservations().stream().map(DemoPlan.WorkReservationPlan::id)).doesNotHaveDuplicates();
        // có ít nhất một tác phẩm nhiều ấn bản được đặt theo cả hai ngôn ngữ
        assertThat(plan.workReservations().stream().filter(w -> w.workId().equals(plan.titles().get(21).workId()))
                .map(DemoPlan.WorkReservationPlan::language)).containsExactlyInAnyOrder("vi", "en");
    }
}
