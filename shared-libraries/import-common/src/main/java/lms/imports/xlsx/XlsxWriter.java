package lms.imports.xlsx;

import java.util.List;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssuedCredential;

/** Ghi file .xlsx (mẫu, file lỗi, file mật khẩu tạm). Mọi giá trị người dùng là ô chuỗi, không bao giờ là công thức. */
public interface XlsxWriter {

    byte[] template(ImportSchema schema);

    /**
     * File lỗi: mỗi sheet của schema gồm các dòng được đưa vào (giữ nguyên giá trị đã nhập) cộng cột "Trạng thái" và
     * "Lỗi"; vẫn có sheet {@code _meta} nên upload lại được sau khi sửa.
     */
    byte[] errorFile(ImportSchema schema, List<ImportRow> rows);

    byte[] credentials(List<IssuedCredential> credentials);
}
