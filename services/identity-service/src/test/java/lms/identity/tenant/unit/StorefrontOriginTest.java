package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.identity.tenant.domain.InvalidStorefrontOriginException;
import lms.identity.tenant.domain.InvalidStorefrontOriginException.Reason;
import lms.identity.tenant.domain.StorefrontOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Chuẩn hóa origin storefront (ADR-0013 2.6, AC4). Domain thuần Java. */
class StorefrontOriginTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "https://shop.example.com|https://shop.example.com",
            "HTTPS://Shop.Example.com/|https://shop.example.com",
            "  https://shop.example.com  |https://shop.example.com",
            "https://shop.example.com:443|https://shop.example.com",
            "https://shop.example.com:8443|https://shop.example.com:8443",
            "https://shop.example.com:80|https://shop.example.com:80",
            "http://localhost:5173|http://localhost:5173",
            "http://localhost:80/|http://localhost",
            "http://127.0.0.1:3000|http://127.0.0.1:3000",
            "https://sub.shop-a.vn|https://sub.shop-a.vn",
            "https://xn--ngn-jla.vn|https://xn--ngn-jla.vn",
            "https://shop.vn/|https://shop.vn"})
    void hopLeDuocChuanHoa(String raw, String expected) {
        assertThat(StorefrontOrigin.parse(raw, false).value()).isEqualTo(expected);
    }

    @Test
    void tenMienQuocTeChuyenSangPunycode() {
        // "ngân.vn" -> xn--ngn-jla.vn ; chữ hoa Unicode cũng về chữ thường
        assertThat(StorefrontOrigin.parse("https://ngân.vn", false).value()).isEqualTo("https://xn--ngn-jla.vn");
        assertThat(StorefrontOrigin.parse("https://NGÂN.vn", false).value()).isEqualTo("https://xn--ngn-jla.vn");
    }

    @Test
    void khacCongHoacSchemeLaKhacNhau() {
        assertThat(StorefrontOrigin.parse("https://shop.vn:8443", false))
                .isNotEqualTo(StorefrontOrigin.parse("https://shop.vn", false));
        assertThat(StorefrontOrigin.parse("http://shop.vn", true))
                .isNotEqualTo(StorefrontOrigin.parse("https://shop.vn", false));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "https://shop.vn/path|PATH",
            "https://shop.vn/a/|PATH",
            "https://shop.vn?x=1|PATH",
            "https://shop.vn/?x=1|PATH",
            "https://shop.vn#frag|PATH",
            "https://user@shop.vn|PATH",
            "https://user:pw@shop.vn|PATH",
            "https://*.shop.vn|WILDCARD",
            "*|WILDCARD",
            "null|NULL",
            "NULL|NULL",
            "shop.vn|SCHEME",
            "//shop.vn|SCHEME",
            "ftp://shop.vn|SCHEME",
            "javascript:alert(1)|SCHEME",
            "https://[::1]:8443|HOST",
            "https://shop.vn.|HOST",
            "https://|SYNTAX",
            "https://shop.vn:0|PORT",
            "https://shop.vn:65536|PORT",
            "https://shop.vn:abc|PORT",
            "http://shop.vn|HTTP_NOT_ALLOWED",
            "http://shop.vn:8080|HTTP_NOT_ALLOWED",
            "https://sho p.vn|SYNTAX"})
    void khongHopLeBiTuChoiKemMaLyDo(String raw, Reason reason) {
        assertThatThrownBy(() -> StorefrontOrigin.parse(raw, false))
                .isInstanceOfSatisfying(InvalidStorefrontOriginException.class,
                        e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void rongNullHoacQuaDaiBiTuChoi() {
        assertThatThrownBy(() -> StorefrontOrigin.parse(null, false)).isInstanceOf(InvalidStorefrontOriginException.class);
        assertThatThrownBy(() -> StorefrontOrigin.parse("   ", false)).isInstanceOf(InvalidStorefrontOriginException.class);
        assertThatThrownBy(() -> StorefrontOrigin.parse("https://" + "a".repeat(300) + ".vn", false))
                .isInstanceOf(InvalidStorefrontOriginException.class);
    }

    @Test
    void httpChiChoPhepNgoaiLocalhostKhiBatCo() {
        assertThat(StorefrontOrigin.parse("http://staging.shop.vn", true).value()).isEqualTo("http://staging.shop.vn");
        assertThat(StorefrontOrigin.parse("http://localhost:3000", false).value()).isEqualTo("http://localhost:3000");
    }
}
