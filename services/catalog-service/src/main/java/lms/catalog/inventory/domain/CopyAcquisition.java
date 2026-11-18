package lms.catalog.inventory.domain;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Thông tin nhập kho và tình trạng bản sao, tất cả tùy chọn: ngày nhập, nguồn nhập, giá mua (VND), tình trạng, ghi chú
 * tình trạng, ngày kiểm kê gần nhất. Quy tắc: ngày không ở tương lai (theo giờ Việt Nam), ngày kiểm kê không trước ngày
 * nhập, giá mua &gt;= 0 và chỉ hợp lệ khi nguồn là PURCHASE, ghi chú tối đa 300 ký tự (rỗng thành null). Sai thì ném
 * {@link InvalidCopyException}. Bất biến.
 */
public record CopyAcquisition(LocalDate acquiredDate, AcquisitionSource acquisitionSource, Long purchasePrice,
                              ConditionGrade conditionGrade, String conditionNote, LocalDate lastInventoryDate) {

    public static final int MAX_NOTE = 300;
    // ZONE phải khai báo trước EMPTY: hàm dựng gọn dùng ZONE khi tạo EMPTY.
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final CopyAcquisition EMPTY = new CopyAcquisition(null, null, null, null, null, null);

    public CopyAcquisition {
        LocalDate today = LocalDate.now(ZONE);
        if (acquiredDate != null && acquiredDate.isAfter(today)) {
            throw new InvalidCopyException("Ngày nhập không được ở tương lai");
        }
        if (lastInventoryDate != null && lastInventoryDate.isAfter(today)) {
            throw new InvalidCopyException("Ngày kiểm kê không được ở tương lai");
        }
        if (acquiredDate != null && lastInventoryDate != null && lastInventoryDate.isBefore(acquiredDate)) {
            throw new InvalidCopyException("Ngày kiểm kê không được trước ngày nhập");
        }
        if (purchasePrice != null && purchasePrice < 0) {
            throw new InvalidCopyException("Giá mua phải >= 0");
        }
        if (purchasePrice != null && acquisitionSource != AcquisitionSource.PURCHASE) {
            throw new InvalidCopyException("Giá mua chỉ hợp lệ khi nguồn nhập là PURCHASE");
        }
        if (conditionNote != null) {
            String s = Normalizer.normalize(conditionNote, Normalizer.Form.NFC).strip();
            if (s.codePointCount(0, s.length()) > MAX_NOTE) {
                throw new InvalidCopyException("Ghi chú tình trạng tối đa " + MAX_NOTE + " ký tự");
            }
            conditionNote = s.isEmpty() ? null : s;
        }
    }

    public boolean isEmpty() {
        return equals(EMPTY);
    }
}
