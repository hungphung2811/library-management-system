package lms.support.audit.application;

/** Lưu envelope thô vào LOG_DB (MongoDB). Use case này chỉ ghi vào MỘT database và không có transaction. */
public class ArchiveRawEventUseCase {

    private final RawEventArchivePort archivePort;

    public ArchiveRawEventUseCase(RawEventArchivePort archivePort) {
        this.archivePort = archivePort;
    }

    public void execute(ArchiveRawEventCommand command) {
        archivePort.archive(command);
    }
}
