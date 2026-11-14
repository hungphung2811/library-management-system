package lms.catalog.inventory.domain;

public class DuplicateBarcodeException extends RuntimeException {
    public DuplicateBarcodeException(String barcode) {
        super("Mã vạch đã tồn tại: " + barcode);
    }
}
