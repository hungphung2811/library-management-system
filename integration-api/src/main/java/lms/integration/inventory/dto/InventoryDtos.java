package lms.integration.inventory.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO v1 của nhóm inventory (allowlist riêng). Không có giá vốn (costPrice), nhà cung cấp, vị trí kệ nội bộ,
 * ghi chú nội bộ, tenantId hay sổ cái tồn kho. Số đếm là số bản; tiền là số nguyên đồng.
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record InventoryItemView(UUID titleId, String titleName, String sku, int onHand, int reserved,
                                    int available, Long sellingPrice, Long effectivePrice, boolean lowStock,
                                    boolean active, Integer lowStockThreshold, Instant lastRestockedAt) {
    }
}
