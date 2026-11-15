package lms.api.circulation.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CollectFinesResult(UUID readerId, int collectedCount, BigDecimal totalCollected, List<FineView> fines) {
}
