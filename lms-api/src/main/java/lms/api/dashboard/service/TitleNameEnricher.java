package lms.api.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.api.dashboard.client.CatalogTitleClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bổ sung {@code titleName} cho mảng phần tử có {@code titleId} bằng một lần tra lô catalog. Không bao giờ ném lỗi:
 * thiếu khóa, lỗi catalog hoặc đầu sách không còn thì {@code titleName = null} (không làm hỏng biểu đồ).
 */
public class TitleNameEnricher {

    private static final Logger log = LoggerFactory.getLogger(TitleNameEnricher.class);
    static final int MAX_IDS = 100;

    private final CatalogTitleClient titles;

    public TitleNameEnricher(CatalogTitleClient titles) {
        this.titles = titles;
    }

    public void fill(JsonNode items) {
        if (items == null || !items.isArray()) {
            return;
        }
        Set<UUID> missing = new LinkedHashSet<>();
        for (JsonNode item : items) {
            if (needsName(item)) {
                UUID id = idOf(item);
                if (id != null && missing.size() < MAX_IDS) {
                    missing.add(id);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<UUID, String> names = Map.of();
        try {
            names = titles.names(List.copyOf(missing));
        } catch (RuntimeException ex) {
            log.warn("Không tra được tên đầu sách ({} id), để titleName = null: {}", missing.size(), ex.toString());
        }
        for (JsonNode item : items) {
            if (item instanceof ObjectNode node && needsName(node)) {
                UUID id = idOf(node);
                node.put("titleName", id == null ? null : names.get(id));
            }
        }
    }

    private static boolean needsName(JsonNode item) {
        return item instanceof ObjectNode && (item.get("titleName") == null || item.get("titleName").isNull());
    }

    private static UUID idOf(JsonNode item) {
        JsonNode raw = item.get("titleId");
        if (raw == null || raw.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(raw.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
