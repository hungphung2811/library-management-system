package lms.imports.core;

import java.util.UUID;

/** Tổng kết cuối của một lần confirm, đưa cho {@link ImportHandler#onCompleted} (audit tóm tắt). */
public record ImportResult(UUID importId, String type, int created, int skippedDuplicate, int failed, boolean completed) {
}
