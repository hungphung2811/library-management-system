package lms.support.audit.application;

public interface RawEventArchivePort {

    /** Lưu envelope thô, idempotent theo eventId (ghi lại cùng eventId thì thay thế, không nhân bản). */
    void archive(ArchiveRawEventCommand command);
}
