package lms.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void giuCorrelationIdTuHeaderVaDonMdcSauKhiXong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                inside.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        });

        assertThat(inside.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private String run(String... headerValues) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (String v : headerValues) request.addHeader(CorrelationIdFilter.HEADER, v);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                inside.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        });
        String out = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(inside.get()).isEqualTo(out);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0123456789abcdef0123456789abcdef",
            "123e4567-e89b-12d3-a456-426614174000",
            "a.b_c-D9",
            "x"})
    void giuNguyenGiaTriHopLe(String value) throws Exception {
        assertThat(run(value)).isEqualTo(value);
    }

    @Test
    void chapNhanDungDoDaiToiDa64() throws Exception {
        String max = "a".repeat(64);
        assertThat(run(max)).isEqualTo(max);
    }

    @Test
    void quaDaiThiSinhMoi() throws Exception {
        String tooLong = "a".repeat(65);
        assertThat(run(tooLong)).isNotEqualTo(tooLong).matches("[0-9a-f-]{36}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc\r\nSet-Cookie: x=1", "abc\ndef", "abc\rdef", "abc\0def",
            "abc def", " abc", "abc ", "abc\t", "tiếng-việt", "abc‮", "a/b", "a:b", "a,b", "abc\n"})
    void khongHopLeThiSinhMoiVaKhongPhanChieu(String bad) throws Exception {
        String out = run(bad);
        assertThat(out).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
    }

    @Test
    void nhieuHeaderTrungThiSinhMoi() throws Exception {
        String out = run("abc-1", "abc-2");
        assertThat(out).isNotIn("abc-1", "abc-2").matches("[0-9a-f-]{36}");
    }

    @Test
    void sinhIdMoiKhiThieuHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
    }
}
