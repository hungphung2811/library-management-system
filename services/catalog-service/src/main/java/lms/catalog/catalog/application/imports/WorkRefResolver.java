package lms.catalog.catalog.application.imports;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.imports.TitleRows.RefLookups;
import lms.catalog.catalog.application.imports.TitleRows.TitleDraft;
import lms.catalog.catalog.application.imports.TitleRows.WorkFacts;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkRepository;
import lms.imports.core.ImportRow;
import lms.imports.core.RowIssue;
import lms.imports.core.RowStatus;

/**
 * Phân giải cột {@code sameWorkAsIsbn} của sheet DauSach (ADR-0026 mục 3.8): dòng trỏ tới ấn bản đã có trong thư viện
 * hoặc tới dòng khác trong cùng file thì gắn vào TÁC PHẨM của ấn bản đó thay vì tạo tác phẩm mới.
 * <ul>
 *   <li>Trỏ tới ISBN không có: {@code WORK_REF_NOT_FOUND}. Trỏ tới dòng INVALID (hoặc dòng bị kéo theo):
 *       {@code WORK_REF_ROW_INVALID}. Vòng tham chiếu (kể cả tự trỏ): {@code WORK_REF_CYCLE}.</li>
 *   <li>Trường cấp tác phẩm dòng nêu mà khác tác phẩm đích: dòng vẫn VALID kèm cảnh báo {@code WORK_FIELD_IGNORED}.</li>
 * </ul>
 * Tra DB theo lô: MỘT truy vấn ấn bản theo ISBN đích và MỘT truy vấn tác phẩm, không theo từng dòng.
 */
final class WorkRefResolver {

    static final String NOT_FOUND = "WORK_REF_NOT_FOUND";
    static final String ROW_INVALID = "WORK_REF_ROW_INVALID";
    static final String CYCLE = "WORK_REF_CYCLE";
    static final String FIELD_IGNORED = "WORK_FIELD_IGNORED";

    private WorkRefResolver() {
    }

    /**
     * Cập nhật {@code out} tại chỗ. {@code drafts} là dòng đầu sách đã dựng được (chỉ dòng VALID lúc dựng),
     * {@code firstByIsbn} là dòng đầu tiên của mỗi ISBN trong file, {@code invalidIsbns} là ISBN của dòng đã hỏng trước đó.
     */
    static void resolve(List<ImportRow> out, Map<Integer, TitleDraft> drafts, Map<String, Integer> firstByIsbn,
                        Set<String> invalidIsbns, RefLookups refs, TitleRepository titles, WorkRepository works) {
        List<Integer> refRows = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (Map.Entry<Integer, TitleDraft> e : drafts.entrySet()) {
            String s = e.getValue().work().sameWorkAsIsbn();
            if (s != null && out.get(e.getKey()).status() == RowStatus.VALID) {
                refRows.add(e.getKey());
                targets.add(s);
            }
        }
        if (refRows.isEmpty()) return;

        Map<String, Title> dbTargets = new HashMap<>();
        for (Title t : titles.findByIsbns(targets)) dbTargets.put(t.isbn(), t);
        Set<UUID> workIds = new HashSet<>();
        for (Title t : dbTargets.values()) if (t.workId() != null) workIds.add(t.workId());
        Map<UUID, Work> dbWorks = new HashMap<>();
        if (!workIds.isEmpty()) for (Work w : works.findByIds(workIds)) dbWorks.put(w.id(), w);

        Map<Integer, Integer> fileParent = new HashMap<>();
        Map<Integer, Work> dbParent = new HashMap<>();
        Deque<Integer> invalidated = new ArrayDeque<>();
        for (int i : refRows) {
            ImportRow row = out.get(i);
            String s = drafts.get(i).work().sameWorkAsIsbn();
            String own = drafts.get(i).title().isbn();
            if (s.equals(own)) {
                invalid(out, i, CYCLE, "Không thể trỏ tới chính ISBN của dòng này", invalidated);
                continue;
            }
            Integer j = firstByIsbn.get(s);
            RowStatus fileStatus = j == null ? null : out.get(j).status();
            if (fileStatus == RowStatus.VALID) {
                fileParent.put(i, j);
                continue;
            }
            Title db = dbTargets.get(s);
            if (db != null) {
                Work w = db.workId() == null ? null : dbWorks.get(db.workId());
                if (w == null) {
                    invalid(out, i, NOT_FOUND, "Ấn bản ISBN " + s + " chưa gắn tác phẩm", invalidated);
                } else {
                    dbParent.put(i, w);
                }
            } else if (fileStatus == RowStatus.INVALID || (j == null && invalidIsbns.contains(s))) {
                invalid(out, i, ROW_INVALID, "Dòng đầu sách ISBN " + s + " không hợp lệ", invalidated);
            } else {
                invalid(out, i, NOT_FOUND, "Không tìm thấy ấn bản ISBN " + s + " trong thư viện hay trong file",
                        invalidated);
            }
        }

        markCycles(out, fileParent, invalidated);

        // Kéo theo: dòng trỏ tới dòng vừa bị INVALID cũng INVALID (lặp theo đồ thị, tuyến tính)
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : fileParent.entrySet()) {
            children.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        while (!invalidated.isEmpty()) {
            int p = invalidated.poll();
            for (int c : children.getOrDefault(p, List.of())) {
                if (out.get(c).status() == RowStatus.VALID) {
                    invalid(out, c, ROW_INVALID, "Dòng đầu sách ISBN " + drafts.get(p).title().isbn()
                            + " (dòng " + out.get(p).rowNumber() + ") không hợp lệ", invalidated);
                }
            }
        }

        warnIgnoredFields(out, drafts, refs, fileParent, dbParent);
    }

    private static void invalid(List<ImportRow> out, int i, String code, String message, Deque<Integer> invalidated) {
        ImportRow row = out.get(i);
        out.set(i, row.invalid(RowIssue.error("sameWorkAsIsbn", code, message)));
        invalidated.add(i);
    }

    /** Dòng nằm trên vòng tham chiếu (a trỏ b, b trỏ a...) đều INVALID WORK_REF_CYCLE. */
    private static void markCycles(List<ImportRow> out, Map<Integer, Integer> fileParent, Deque<Integer> invalidated) {
        Map<Integer, Integer> color = new HashMap<>(); // 1 đang đi, 2 xong
        for (int start : new ArrayList<>(fileParent.keySet())) {
            if (color.containsKey(start)) continue;
            List<Integer> path = new ArrayList<>();
            int cur = start;
            while (true) {
                Integer c = color.get(cur);
                if (c != null) {
                    if (c == 1) {
                        for (int k = path.indexOf(cur); k < path.size(); k++) {
                            int node = path.get(k);
                            if (out.get(node).status() == RowStatus.VALID) {
                                invalid(out, node, CYCLE, "Vòng tham chiếu cùng tác phẩm giữa các dòng", invalidated);
                            }
                        }
                    }
                    break;
                }
                color.put(cur, 1);
                path.add(cur);
                Integer next = fileParent.get(cur);
                if (next == null) break;
                cur = next;
            }
            for (int node : path) color.put(node, 2);
        }
    }

    private static void warnIgnoredFields(List<ImportRow> out, Map<Integer, TitleDraft> drafts, RefLookups refs,
                                          Map<Integer, Integer> fileParent, Map<Integer, Work> dbParent) {
        List<Integer> joined = new ArrayList<>(fileParent.keySet());
        joined.addAll(dbParent.keySet());
        for (int i : joined) {
            if (out.get(i).status() != RowStatus.VALID) continue;
            int root = i;
            int hops = 0;
            while (fileParent.containsKey(root) && hops++ <= fileParent.size()) root = fileParent.get(root);
            WorkFacts target = dbParent.containsKey(root) ? TitleRows.factsOf(dbParent.get(root))
                    : TitleRows.effectiveFactsOf(drafts.get(root), refs);
            List<String> differing = TitleRows.factsOf(drafts.get(i), refs).differencesFrom(target);
            if (!differing.isEmpty()) {
                out.set(i, out.get(i).withIssue(RowIssue.warning(differing.get(0), FIELD_IGNORED,
                        "Cột cấp tác phẩm khác tác phẩm đích nên bị bỏ qua, dùng giá trị của tác phẩm: "
                                + String.join(", ", differing))));
            }
        }
    }

    /**
     * Thứ tự ghi: dòng không trỏ (hoặc trỏ tới ấn bản đã có trong DB) trước, dòng trỏ tới dòng cùng file sau dòng đích
     * (độ sâu theo chuỗi tham chiếu), giữ thứ tự dòng trong cùng độ sâu. Dòng vòng/không hợp lệ không đến được đây.
     */
    static List<ImportRow> orderParentsFirst(List<ImportRow> titleRows) {
        Map<String, ImportRow> byIsbn = new HashMap<>();
        for (ImportRow r : titleRows) {
            String isbn = ImportValues.text(r, "isbn");
            if (isbn != null) byIsbn.putIfAbsent(isbn, r);
        }
        Map<ImportRow, Integer> depth = new HashMap<>();
        for (ImportRow r : titleRows) depth.put(r, depthOf(r, byIsbn));
        List<ImportRow> sorted = new ArrayList<>(titleRows);
        sorted.sort(java.util.Comparator.comparingInt(depth::get)); // sắp ổn định: giữ thứ tự dòng cùng độ sâu
        return sorted;
    }

    private static int depthOf(ImportRow row, Map<String, ImportRow> byIsbn) {
        int depth = 0;
        ImportRow cur = row;
        Set<ImportRow> seen = new HashSet<>();
        while (seen.add(cur)) {
            String s = ImportValues.text(cur, "sameWorkAsIsbn");
            ImportRow parent = s == null ? null : byIsbn.get(s);
            if (parent == null) break;
            depth++;
            cur = parent;
        }
        return depth;
    }
}
