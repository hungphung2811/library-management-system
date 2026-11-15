package lms.api.circulation.dto;

import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** Trả theo bookCopyId hoặc barcode (máy quét). condition: GOOD (mặc định) hoặc DAMAGED. */
public record ReturnCopyRequest(UUID bookCopyId, String barcode,
                                @Pattern(regexp = "GOOD|DAMAGED") String condition) {
}
