package lms.identity.apiclient.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationScope;
import lms.identity.apiclient.domain.ClientSecret;
import lms.identity.apiclient.domain.SiteKey;
import org.junit.jupiter.api.Test;

/** Quy tắc thuần của domain: secret (D7), site key, danh mục scope (Q5/Q6). */
class ApiClientDomainTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void secretCoTienToDoDaiVaHintVaKhongTrung() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ClientSecret s = ClientSecret.generate(random);
            assertThat(s.plain()).matches("lms_sk_[A-Za-z0-9_-]{43}");
            assertThat(s.hash()).matches("[0-9a-f]{64}");
            assertThat(s.hint()).hasSize(4).isEqualTo(s.plain().substring(s.plain().length() - 4));
            assertThat(seen.add(s.plain())).isTrue();
        }
    }

    @Test
    void soSanhBangHashKhongChapNhanSaiHoacNull() {
        ClientSecret s = ClientSecret.generate(random);
        assertThat(ClientSecret.matches(s.plain(), s.hash())).isTrue();
        assertThat(ClientSecret.matches(s.plain().toUpperCase(), s.hash())).isFalse();
        assertThat(ClientSecret.matches(null, s.hash())).isFalse();
        assertThat(ClientSecret.matches(s.plain(), null)).isFalse();
        assertThat(ClientSecret.matches(s.plain(), "0".repeat(64))).isFalse();
        // vector SHA-256 đã biết ("abc")
        assertThat(ClientSecret.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void toStringKhongBaoGioInSecretHoacHash() {
        ClientSecret s = ClientSecret.generate(random);
        assertThat(s.toString()).doesNotContain(s.plain()).doesNotContain(s.hash());
    }

    @Test
    void siteKeyDungDinhDangCheCuaDb() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String key = SiteKey.generate(random);
            assertThat(key).matches("lms_pk_[A-Za-z0-9_-]{32}");
            assertThat(SiteKey.isValid(key)).isTrue();
            assertThat(seen.add(key)).isTrue();
        }
        assertThat(SiteKey.isValid("lms_sk_" + "a".repeat(32))).isFalse();
        assertThat(SiteKey.isValid("lms_pk_short")).isFalse();
        assertThat(SiteKey.isValid(null)).isFalse();
    }

    @Test
    void danhMucScopeChiCapScopeDoc() {
        assertThat(ApplicationScope.values()).extracting(ApplicationScope::code).containsExactlyInAnyOrder(
                "books:read", "books:write", "inventory:read", "inventory:write", "orders:read", "orders:write",
                "customers:read", "customers:write");
        assertThat(ApplicationScope.values()).filteredOn(ApplicationScope::grantable)
                .extracting(ApplicationScope::code).containsExactlyInAnyOrder("books:read", "inventory:read",
                        "orders:read");
        assertThatThrownBy(() -> ApplicationScope.parse("Books:Read")).isInstanceOfSatisfying(
                ApplicationException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_SCOPE));
        assertThatThrownBy(() -> ApplicationScope.CUSTOMERS_READ.requireGrantable()).isInstanceOfSatisfying(
                ApplicationException.class, e -> assertThat(e.reason()).isEqualTo(Reason.SCOPE_NOT_GRANTABLE));
    }
}
