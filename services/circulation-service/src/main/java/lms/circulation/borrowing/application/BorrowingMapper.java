package lms.circulation.borrowing.application;

import lms.circulation.borrowing.application.BorrowBookRequest;
import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.ReturnBookRequest;
import lms.circulation.borrowing.application.BorrowBookCommand;
import lms.circulation.borrowing.application.ReturnBookCommand;
import lms.circulation.borrowing.domain.BorrowingRecord;
import java.util.List;
import java.util.UUID;

public final class BorrowingMapper {

    private BorrowingMapper() {
    }

    public static BorrowBookCommand toCommand(BorrowBookRequest request) {
        return new BorrowBookCommand(request.userId(), request.bookCopyIds());
    }

    public static ReturnBookCommand toCommand(UUID borrowingId, ReturnBookRequest request) {
        return new ReturnBookCommand(borrowingId, request.bookCopyId());
    }

    public static BorrowingResponse toResponse(BorrowingRecord record) {
        List<BorrowingResponse.Item> items = record.items().stream()
                .map(i -> new BorrowingResponse.Item(i.bookCopyId(), i.returnDate()))
                .toList();
        return new BorrowingResponse(record.id().value(), record.userId(), record.status().name(),
                record.borrowDate(), record.dueDate(), record.renewCount(), items);
    }
}
