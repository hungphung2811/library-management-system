package lms.api.circulation.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReaderFinesView(UUID readerId, BigDecimal totalUnpaid, List<FineView> fines) {
}
