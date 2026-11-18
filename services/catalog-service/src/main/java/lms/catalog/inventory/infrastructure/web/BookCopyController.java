package lms.catalog.inventory.infrastructure.web;

import lms.security.permission.CrossTenantReadable;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import lms.catalog.inventory.application.AddCopyCommand;
import lms.catalog.inventory.application.AddCopyRequest;
import lms.catalog.inventory.application.AddCopyUseCase;
import lms.catalog.inventory.application.ChangeCopyStatusRequest;
import lms.catalog.inventory.application.ChangeCopyStatusUseCase;
import lms.catalog.inventory.application.CopyDetailsResponse;
import lms.catalog.inventory.application.CopyResponse;
import lms.catalog.inventory.application.GetCopyUseCase;
import lms.catalog.inventory.application.CopyLookupView;
import lms.catalog.inventory.application.ListCopiesUseCase;
import lms.catalog.inventory.application.LookupCopiesRequest;
import lms.catalog.inventory.application.LookupCopiesUseCase;
import lms.catalog.inventory.application.MoveCopyShelfLocationRequest;
import lms.catalog.inventory.application.MoveCopyShelfLocationUseCase;
import lms.catalog.inventory.application.UpdateCopyAcquisitionRequest;
import lms.catalog.inventory.application.UpdateCopyAcquisitionUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/book-copies")
public class BookCopyController {

    private final AddCopyUseCase addCopy;
    private final ListCopiesUseCase listCopies;

    private final GetCopyUseCase getCopy;
    private final ChangeCopyStatusUseCase changeStatus;
    private final MoveCopyShelfLocationUseCase moveShelfLocation;
    private final LookupCopiesUseCase lookupCopies;
    private final UpdateCopyAcquisitionUseCase updateAcquisition;

    public BookCopyController(AddCopyUseCase addCopy, ListCopiesUseCase listCopies, GetCopyUseCase getCopy,
                              ChangeCopyStatusUseCase changeStatus, MoveCopyShelfLocationUseCase moveShelfLocation,
                              LookupCopiesUseCase lookupCopies, UpdateCopyAcquisitionUseCase updateAcquisition) {
        this.updateAcquisition = updateAcquisition;
        this.lookupCopies = lookupCopies;
        this.moveShelfLocation = moveShelfLocation;
        this.addCopy = addCopy;
        this.listCopies = listCopies;
        this.getCopy = getCopy;
        this.changeStatus = changeStatus;
    }

    /** Tra bản sao theo id: trạng thái, đầu sách và giá bìa (circulation kiểm tra trước khi mượn). */
    @GetMapping("/{id}")
    public CopyDetailsResponse get(@PathVariable UUID id) {
        return getCopy.byId(id);
    }

    /** Tra bản sao theo mã vạch (thủ thư quét mã khi trả/báo mất). */
    @GetMapping("/by-barcode/{barcode}")
    public CopyDetailsResponse getByBarcode(@PathVariable String barcode) {
        return getCopy.byBarcode(barcode);
    }

    /** Đổi trạng thái bản sao (LOANED/AVAILABLE/DAMAGED/LOST...), idempotent; sai vòng đời trả 409. */
    @PutMapping("/{id}/status")
    public CopyResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeCopyStatusRequest request) {
        return changeStatus.execute(id, request.status());
    }

    /** Gán/đổi/gỡ vị trí kệ của bản sao (shelfLocationId null = gỡ). Khóa BookCopyController/moveShelfLocation. */
    @PutMapping("/{id}/shelf-location")
    public CopyResponse moveShelfLocation(@PathVariable UUID id,
                                          @Valid @RequestBody MoveCopyShelfLocationRequest request) {
        return moveShelfLocation.execute(id, request.shelfLocationId());
    }

    /**
     * Thay TOÀN BỘ thông tin nhập kho và tình trạng của bản sao (thiếu trường = xóa). Khóa
     * BookCopyController/updateAcquisition.
     */
    @PutMapping("/{id}/acquisition")
    public CopyResponse updateAcquisition(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateCopyAcquisitionRequest request) {
        return updateAcquisition.execute(id, request.acquisition());
    }

    /**
     * Tra bản sao theo lô (tối đa 100 id), kèm tên/ISBN đầu sách; id không có hoặc thuộc tenant khác thì vắng.
     * Khóa BookCopyController/lookup (ADR-0018): không đổi tên.
     */
    @PostMapping("/lookup")
    public List<CopyLookupView> lookup(@Valid @RequestBody LookupCopiesRequest request) {
        return lookupCopies.execute(request.ids());
    }

    @PostMapping
    public ResponseEntity<CopyResponse> add(@Valid @RequestBody AddCopyRequest request) {
        CopyResponse response = addCopy.execute(new AddCopyCommand(request.titleId(), request.barcode(), request.shelfLocationId(),
                request.acquisition()));
        return ResponseEntity.created(URI.create("/internal/book-copies/" + response.id())).body(response);
    }

    @GetMapping
    @CrossTenantReadable
    public List<CopyResponse> list(@RequestParam UUID titleId) {
        return listCopies.execute(titleId);
    }
}
