package lms.imports.web;

import java.io.IOException;
import java.util.UUID;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportViews;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportWorkflow;
import lms.security.permission.AuthenticatedOnly;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * 7 endpoint chung của mọi loại nhập (ADR-0024 mục 3.3). Controller con chỉ cần:
 * <pre>
 * &#64;RestController
 * &#64;RequestMapping("/internal/imports/authors")
 * public class AuthorImportController extends AbstractImportController {
 *     public AuthorImportController(ImportWorkflow w, AuthorImportHandler h, ImportActorResolver a) { super(w, h, a); }
 * }
 * </pre>
 * Khóa phân quyền theo LỚP CON (PermissionKeyResolver dùng lớp bean thật): {@code AuthorImportController/upload} và
 * {@code AuthorImportController/confirm}. Các endpoint còn lại {@code @AuthenticatedOnly} và kiểm chủ phiên trong
 * workflow (tenant + sub khớp khóa Redis, sai thì 404 IMPORT_NOT_FOUND). Không đổi tên method ở đây sau khi phát hành
 * (đổi khóa quyền của mọi loại).
 */
public abstract class AbstractImportController {

    public static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ImportWorkflow workflow;
    private final ImportHandler handler;
    private final ImportActorResolver actors;

    protected AbstractImportController(ImportWorkflow workflow, ImportHandler handler, ImportActorResolver actors) {
        this.workflow = workflow;
        this.handler = handler;
        this.actors = actors;
    }

    @GetMapping("/template")
    @AuthenticatedOnly
    public ResponseEntity<byte[]> template() {
        return download(workflow.template(handler), "mau-" + handler.type() + ".xlsx");
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ImportViews.SessionView> upload(@RequestPart(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ImportException(ImportErrorCode.FILE_REQUIRED);
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, ImportErrorCode.FILE_CORRUPT.defaultMessage(),
                    java.util.List.of(), e);
        }
        ImportActor actor = actors.current();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(workflow.upload(handler, actor, file.getOriginalFilename(), data));
    }

    @GetMapping("/{importId}")
    @AuthenticatedOnly
    public ImportViews.SessionView get(@PathVariable UUID importId) {
        return workflow.get(handler, actors.current(), importId);
    }

    /** Query record {@code ImportViews.RowsQuery(status, sheet, page, size)}, size tối đa 200. */
    @GetMapping("/{importId}/rows")
    @AuthenticatedOnly
    public ImportViews.RowsPage rows(@PathVariable UUID importId, ImportViews.RowsQuery query) {
        return workflow.rows(handler, actors.current(), importId, query);
    }

    /** 202 khi đang ghi; 200 khi phiên đã xong (bấm lại không ghi lại, trả trạng thái hiện tại). */
    @PostMapping("/{importId}/confirm")
    public ResponseEntity<ImportViews.SessionView> confirm(@PathVariable UUID importId) {
        ImportViews.SessionView view = workflow.confirm(handler, actors.current(), importId);
        HttpStatus status = view.status() == ImportSessionStatus.COMMITTED || view.status() == ImportSessionStatus.COMMIT_FAILED
                ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(view);
    }

    @DeleteMapping("/{importId}")
    @AuthenticatedOnly
    public ResponseEntity<Void> cancel(@PathVariable UUID importId) {
        workflow.cancel(handler, actors.current(), importId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{importId}/error-file")
    @AuthenticatedOnly
    public ResponseEntity<byte[]> errorFile(@PathVariable UUID importId) {
        byte[] file = workflow.errorFile(handler, actors.current(), importId);
        return download(file, "loi-" + handler.type() + ".xlsx");
    }

    /**
     * Dành cho controller của identity: khai báo method {@code credentials} riêng (khóa
     * {@code UserImportController/credentials}) rồi gọi hàm này. Tải một lần, chỉ sau COMMITTED.
     */
    protected ResponseEntity<byte[]> credentialsDownload(UUID importId) {
        byte[] file = workflow.credentialsFile(handler, actors.current(), importId);
        return download(file, "mat-khau-tam-" + handler.type() + ".xlsx");
    }

    protected static ResponseEntity<byte[]> download(byte[] body, String asciiName) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(asciiName).build().toString())
                .contentLength(body.length)
                .body(body);
    }
}
