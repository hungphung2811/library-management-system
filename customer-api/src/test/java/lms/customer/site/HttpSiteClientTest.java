package lms.customer.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lms.customer.site.client.CachedSiteClient;
import lms.customer.site.client.SiteClient;
import lms.customer.site.client.http.HttpSiteClient;
import lms.customer.site.dto.SiteDtos.BusinessType;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpSiteClientTest {

    @Test
    void docTrangVaGhiFormDungDuongDanNoiBo() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://system");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSiteClient client = new HttpSiteClient(builder.build());

        server.expect(requestTo("http://system/internal/cms/public/pages")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"slug\":\"gioi-thieu\",\"title\":\"Giới thiệu\",\"sortOrder\":1,\"x\":1}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/public/pages/gioi-thieu"))
                .andRespond(withSuccess("{\"slug\":\"gioi-thieu\",\"title\":\"Giới thiệu\",\"bodyHtml\":\"<p>a</p>\","
                        + "\"publishedAt\":\"2026-11-16T01:00:00Z\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://system/internal/cms/public/contact-requests")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.fullName").value("An"))
                .andExpect(jsonPath("$.businessType").value("LIBRARY"))
                .andExpect(jsonPath("$.consent").value(true))
                .andExpect(jsonPath("$.organization").value("Thư viện X"))
                .andExpect(jsonPath("$.email").value("an@x.vn"))
                .andExpect(jsonPath("$.message").value("Hi"))
                .andExpect(jsonPath("$.phone").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("website"))))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertEquals("gioi-thieu", client.listPages().get(0).slug());
        PageView page = client.getPage("gioi-thieu");
        assertEquals("<p>a</p>", page.bodyHtml());
        client.submitContactRequest(new ContactSubmission("An", "an@x.vn", null, "Thư viện X", BusinessType.LIBRARY, "Hi", true));
        server.verify();
    }

    @Test
    void boDemChiDemKetQuaThanhCongTrong60Giay() {
        AtomicInteger listCalls = new AtomicInteger();
        AtomicInteger pageCalls = new AtomicInteger();
        SiteClient delegate = new SiteClient() {
            @Override
            public List<PageSummaryView> listPages() {
                listCalls.incrementAndGet();
                return List.of(new PageSummaryView("a", "A", 1));
            }

            @Override
            public PageView getPage(String slug) {
                pageCalls.incrementAndGet();
                return new PageView(slug, "T", "<p/>", null);
            }

            @Override
            public void submitContactRequest(ContactSubmission submission) {
            }
        };
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        CachedSiteClient cached = new CachedSiteClient(delegate, Duration.ofSeconds(60), 10, ticker);

        cached.listPages();
        cached.listPages();
        cached.getPage("a");
        cached.getPage("a");
        assertEquals(1, listCalls.get());
        assertEquals(1, pageCalls.get());
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        cached.getPage("a");
        cached.listPages();
        assertEquals(2, listCalls.get());
        assertEquals(2, pageCalls.get());
    }
}
