package lms.sales.shared.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PersonalDataMaskerTest {

    @Test
    void masksNameKeepingFirstLetterOfEachWord() {
        assertEquals("N***** V** A*", PersonalDataMasker.maskName("Nguyen Van An"));
        assertEquals("T***", PersonalDataMasker.maskName("  Tran "));
        assertNull(PersonalDataMasker.maskName(null));
    }

    @Test
    void masksPhoneKeepingLastThreeDigits() {
        assertEquals("*******678", PersonalDataMasker.maskPhone("0912345678"));
        assertEquals("***", PersonalDataMasker.maskPhone("123"));
        assertNull(PersonalDataMasker.maskPhone(""));
    }

    @Test
    void masksEmailKeepingFirstLetterAndDomain() {
        assertEquals("b***@example.vn", PersonalDataMasker.maskEmail("binh@example.vn"));
        assertEquals("a***@x.com", PersonalDataMasker.maskEmail(" a@x.com "));
        assertEquals("***", PersonalDataMasker.maskEmail("khong-co-a-cong"));
        assertNull(PersonalDataMasker.maskEmail(" "));
        assertNull(PersonalDataMasker.maskEmail(null));
    }
}
