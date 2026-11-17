package lms.customer.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Chuỗi không được chứa ký tự điều khiển (NUL, ESC, DEL, C1...), khớp quy tắc TextRules của system-service (NUL làm
 * PostgreSQL từ chối ghi). {@link #allowLineBreaksAndTabs()} cho phép \n, \r, \t (ô nhiều dòng như lời nhắn). Thông
 * điệp lỗi cố định, không kèm giá trị bị từ chối.
 */
@Documented
@Constraint(validatedBy = NoControlCharsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoControlChars {

    String message() default "chứa ký tự điều khiển không hợp lệ";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    boolean allowLineBreaksAndTabs() default false;
}
