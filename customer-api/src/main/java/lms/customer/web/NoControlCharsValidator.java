package lms.customer.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NoControlCharsValidator implements ConstraintValidator<NoControlChars, CharSequence> {

    private boolean allowLineBreaksAndTabs;

    @Override
    public void initialize(NoControlChars annotation) {
        this.allowLineBreaksAndTabs = annotation.allowLineBreaksAndTabs();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(allowLineBreaksAndTabs && (c == '\n' || c == '\r' || c == '\t'))) {
                return false;
            }
        }
        return true;
    }
}
