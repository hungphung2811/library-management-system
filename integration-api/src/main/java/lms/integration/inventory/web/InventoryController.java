package lms.integration.inventory.web;

import java.util.UUID;
import lms.integration.common.PageParams;
import lms.integration.common.dto.PageView;
import lms.integration.inventory.client.InventoryClient;
import lms.integration.inventory.dto.InventoryDtos.InventoryItemView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tồn kho (scope {@code inventory:read}; tenant phải bật module SALES, do sales-service kiểm). Chỉ đọc. */
@RestController
@RequestMapping("/api/integration/v1/inventory")
public class InventoryController {

    /** page bắt đầu từ 0 (mặc định 0), size 1..100 (mặc định 20), lowStockOnly mặc định false. */
    public record InventoryQuery(Boolean lowStockOnly, Integer page, Integer size) {
    }

    private final InventoryClient inventory;

    public InventoryController(InventoryClient inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public ResponseEntity<PageView<InventoryItemView>> list(InventoryQuery query) {
        return noStore(inventory.list(Boolean.TRUE.equals(query.lowStockOnly()), PageParams.page(query.page()),
                PageParams.size(query.size())));
    }

    @GetMapping("/{titleId}")
    public ResponseEntity<InventoryItemView> get(@PathVariable UUID titleId) {
        return noStore(inventory.get(titleId));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
