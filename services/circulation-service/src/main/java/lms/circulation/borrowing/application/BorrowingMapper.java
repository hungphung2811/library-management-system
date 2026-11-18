package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingItem;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.circulation.fine.application.FineResponse;
import java.util.List;
import java.util.UUID;

public final class BorrowingMapper {

    private BorrowingMapper() {
    }

    public static BorrowBookCommand toCommand(BorrowBookRequest request) {
        List<BorrowBookCommand.ConditionOverride> overrides = request.items() == null ? List.of()
                : request.items().stream()
                .map(i -> new BorrowBookCommand.ConditionOverride(i.bookCopyId(), i.conditionGrade(), i.conditionNote()))
                .toList();
        return new BorrowBookCommand(request.userId(), request.bookCopyIds(), overrides);
    }

    public static ReturnBookCommand toCommand(UUID borrowingId, ReturnBookRequest request) {
        return new ReturnBookCommand(borrowingId, request.bookCopyId(), request.barcode(), condition(request.condition()),
                request.damageLevelCode(), request.damageNote(), request.evidenceUrls());
    }

    public static ReportLostCommand toCommand(ReportLostRequest request) {
        return new ReportLostCommand(request.bookCopyId(), request.barcode(), request.damageNote(), request.evidenceUrls());
    }

    private static ItemCondition condition(ItemCondition condition) {
        return condition == null ? ItemCondition.GOOD : condition;
    }

    public static BorrowingResponse toResponse(BorrowingRecord record) {
        return toResponse(record, List.of());
    }

    public static BorrowingResponse toResponse(BorrowingRecord record, List<FineResponse> fines) {
        List<BorrowingResponse.Item> items = record.items().stream()
                .map(BorrowingMapper::toItem)
                .toList();
        return new BorrowingResponse(record.id().value(), record.userId(), record.status().name(),
                record.borrowDate(), record.dueDate(), record.renewCount(), items, fines, record.tenantId());
    }

    private static BorrowingResponse.Item toItem(BorrowingItem i) {
        BorrowingResponse.ConditionAtBorrow atBorrow = i.borrowCondition() == null ? null
                : new BorrowingResponse.ConditionAtBorrow(
                        i.borrowCondition().grade() == null ? null : i.borrowCondition().grade().name(),
                        i.borrowCondition().note());
        return new BorrowingResponse.Item(i.bookCopyId(), i.returnDate(), i.condition() == null ? null : i.condition().name(),
                atBorrow);
    }
}
