package lms.imports.core;

import java.util.List;
import java.util.Optional;

/**
 * Port lưu phiên preview. Cài đặt phải FAIL-CLOSED: mọi lỗi hạ tầng ném {@link ImportException}
 * {@code STORAGE_UNAVAILABLE}, không bao giờ âm thầm bỏ qua. Mọi thao tác ghi trạng thái là nguyên tử.
 */
public interface ImportSessionStore {

    /** Lưu phiên mới (meta + dòng + đăng ký phiên mở) nguyên tử. {@code SESSION_LIMIT} nếu người dùng đã đủ phiên mở. */
    void create(ImportSessionMeta meta, List<ImportRow> rows);

    Optional<ImportSessionMeta> findMeta(SessionKey key);

    /** Dòng của phiên; rỗng nếu phiên không tồn tại. */
    List<ImportRow> loadRows(SessionKey key);

    /**
     * CAS trạng thái: VALIDATED (hoặc COMMITTING mà heartbeat cũ hơn ngưỡng) thành COMMITTING với {@code commitToken} mới.
     * Không phải lúc đó thì không đổi gì và trả trạng thái hiện tại.
     */
    BeginResult beginCommit(SessionKey key, String commitToken, int totalChunks);

    /** Trả phiên COMMITTING (đúng token) về VALIDATED, dùng khi hàng đợi nền đầy. */
    void releaseCommit(SessionKey key, String commitToken);

    /**
     * Ghi tiến độ sau một chunk, chỉ khi {@code commitToken} vẫn khớp (luồng cũ "sống lại" không ghi đè). Trả false nếu
     * token không khớp hoặc phiên đã mất: luồng phải dừng.
     */
    boolean recordProgress(SessionKey key, String commitToken, Progress progress, List<RowOutcome> outcomes,
                           List<IssuedCredential> credentials);

    /** Kết thúc (COMMITTED hoặc COMMIT_FAILED) và gia hạn TTL. Trả false nếu token không khớp. */
    boolean finish(SessionKey key, String commitToken, ImportSessionStatus finalStatus, String errorCode);

    List<RowOutcome> loadOutcomes(SessionKey key);

    /** Lấy và xóa mật khẩu tạm (một lần). Rỗng nếu đã lấy/hết hạn. */
    Optional<List<IssuedCredential>> takeCredentials(SessionKey key);

    DeleteResult delete(SessionKey key);

    record BeginResult(boolean acquired, ImportSessionStatus current) {
    }

    record Progress(int nextChunk, int processed, int created, int skipped, int failed) {
    }

    enum DeleteResult { DELETED, NOT_FOUND, COMMITTING }
}
