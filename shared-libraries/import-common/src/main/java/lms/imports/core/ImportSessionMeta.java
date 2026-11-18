package lms.imports.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Metadata của phiên (lưu dạng HASH trong Redis; mọi trường là chuỗi). Thời điểm là epoch millis.
 * Các trường commit ({@code commitToken}...{@code errorCode}) chỉ có nghĩa từ khi confirm.
 */
public record ImportSessionMeta(UUID importId, String type, int templateVersion, UUID tenantId, String userId,
                                String fileName, ImportSessionStatus status, long createdAt, long expiresAt,
                                List<SheetStats> sheets, int rowsBytes, String commitToken, int nextChunk,
                                int totalChunks, int processed, int toCommit, int created, int skipped, int failed,
                                long heartbeatAt, long finishedAt, String errorCode) {

    public ImportSessionMeta {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        commitToken = commitToken == null ? "" : commitToken;
        errorCode = errorCode == null ? "" : errorCode;
    }

    /** Phiên mới sau upload (VALIDATED). */
    public static ImportSessionMeta validated(UUID importId, String type, int templateVersion, UUID tenantId, String userId,
                                              String fileName, long now, long expiresAt, List<SheetStats> sheets,
                                              int rowsBytes) {
        int valid = sheets.stream().mapToInt(SheetStats::valid).sum();
        return new ImportSessionMeta(importId, type, templateVersion, tenantId, userId, fileName,
                ImportSessionStatus.VALIDATED, now, expiresAt, sheets, rowsBytes, "", 0, 0, 0, valid, 0, 0, 0, 0, 0, "");
    }

    public SessionKey key() {
        return new SessionKey(tenantId, userId, importId);
    }

    /** Trạng thái hiển thị: COMMITTING mà heartbeat quá cũ thì là INTERRUPTED. */
    public ImportSessionStatus effectiveStatus(long nowMillis, long staleMillis) {
        if (status == ImportSessionStatus.COMMITTING && nowMillis - heartbeatAt > staleMillis) {
            return ImportSessionStatus.INTERRUPTED;
        }
        return status;
    }

    public Map<String, String> toHash() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("importId", importId.toString());
        h.put("type", type);
        h.put("templateVersion", Integer.toString(templateVersion));
        h.put("tenantId", tenantId.toString());
        h.put("userId", userId);
        h.put("fileName", fileName);
        h.put("status", status.name());
        h.put("createdAt", Long.toString(createdAt));
        h.put("expiresAt", Long.toString(expiresAt));
        h.put("sheets", encodeSheets(sheets));
        h.put("rowsBytes", Integer.toString(rowsBytes));
        h.put("commitToken", commitToken);
        h.put("nextChunk", Integer.toString(nextChunk));
        h.put("totalChunks", Integer.toString(totalChunks));
        h.put("processed", Integer.toString(processed));
        h.put("toCommit", Integer.toString(toCommit));
        h.put("created", Integer.toString(created));
        h.put("skipped", Integer.toString(skipped));
        h.put("failed", Integer.toString(failed));
        h.put("heartbeatAt", Long.toString(heartbeatAt));
        h.put("finishedAt", Long.toString(finishedAt));
        h.put("errorCode", errorCode);
        return h;
    }

    public static ImportSessionMeta fromHash(Map<String, String> h) {
        return new ImportSessionMeta(UUID.fromString(h.get("importId")), h.get("type"), intOf(h, "templateVersion"),
                UUID.fromString(h.get("tenantId")), h.get("userId"), h.getOrDefault("fileName", ""),
                ImportSessionStatus.valueOf(h.get("status")), longOf(h, "createdAt"), longOf(h, "expiresAt"),
                decodeSheets(h.getOrDefault("sheets", "")), intOf(h, "rowsBytes"), h.get("commitToken"),
                intOf(h, "nextChunk"), intOf(h, "totalChunks"), intOf(h, "processed"), intOf(h, "toCommit"),
                intOf(h, "created"), intOf(h, "skipped"), intOf(h, "failed"), longOf(h, "heartbeatAt"),
                longOf(h, "finishedAt"), h.get("errorCode"));
    }

    private static int intOf(Map<String, String> h, String k) {
        String v = h.get(k);
        return v == null || v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    private static long longOf(Map<String, String> h, String k) {
        String v = h.get(k);
        return v == null || v.isEmpty() ? 0L : Long.parseLong(v);
    }

    /** Mã hóa: {@code sheet,total,valid,invalid,duplicate,warning} nối bằng {@code |}; tên sheet không được chứa {@code , |}. */
    static String encodeSheets(List<SheetStats> sheets) {
        StringBuilder sb = new StringBuilder();
        for (SheetStats s : sheets) {
            if (sb.length() > 0) sb.append('|');
            sb.append(s.sheet().replace(',', ' ').replace('|', ' ')).append(',').append(s.total()).append(',')
                    .append(s.valid()).append(',').append(s.invalid()).append(',').append(s.duplicate()).append(',')
                    .append(s.warning());
        }
        return sb.toString();
    }

    static List<SheetStats> decodeSheets(String text) {
        List<SheetStats> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String part : text.split("\\|")) {
            String[] f = part.split(",");
            out.add(new SheetStats(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5])));
        }
        return out;
    }
}
