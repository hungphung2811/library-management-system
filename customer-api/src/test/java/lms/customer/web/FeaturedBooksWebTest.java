package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.customer.catalog.FeaturedBooks;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;
import lms.customer.catalog.web.BookController;
import lms.customer.catalog.web.FeaturedBookController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /public/t/{code}/books/featured: công khai, trường = BookSummaryView, max-age=60, 404 chung, không rơi vào BookController.get. */
@WebMvcTest({FeaturedBookController.class, BookController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class})
class FeaturedBooksWebTest {

    private final UUID bookId = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean FeaturedBooks featured;
    @MockitoBean CatalogClient catalog;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void shopA() {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(UUID.randomUUID(), "shop-a", "Hiệu sách A", "svc-token-a", 300));
    }

    @Test
    void congKhaiBoQuaAuthorizationTruongDungBookSummaryViewVaCache60() throws Exception {
        when(featured.featured("svc-token-a")).thenReturn(List.of(
                new BookSummaryView(bookId, "Nhà giả kim", "Paulo Coelho", 79_000, true, false)));
        String body = mvc.perform(get("/public/t/shop-a/books/featured")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer rac.khong.hop.le"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookRef").value(bookId.toString()))
                .andExpect(jsonPath("$[0].title").value("Nhà giả kim"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode first = new ObjectMapper().readTree(body).get(0);
        Set<String> actual = new HashSet<>();
        first.fieldNames().forEachRemaining(actual::add);
        Set<String> expected = new HashSet<>();
        Arrays.stream(BookSummaryView.class.getRecordComponents()).forEach(c -> expected.add(c.getName()));
        assertThat(actual).isEqualTo(expected);
        mvc.perform(head("/public/t/shop-a/books/featured")).andExpect(status().isOk());
    }

    @Test
    void routeLiteralKhongRoiVaoBookControllerGet() throws Exception {
        when(featured.featured("svc-token-a")).thenReturn(List.of());
        mvc.perform(get("/public/t/shop-a/books/featured")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verifyNoInteractions(catalog);
        verify(featured).featured("svc-token-a");
    }

    @Test
    void cuaHangKhongKhaDungLa404KhongGoiSales() throws Exception {
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(get("/public/t/khong-co/books/featured")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(featured, catalog);
    }

    @Test
    void loiDownstreamThanh503ChungVaPhuongThucKhacBiChan() throws Exception {
        when(featured.featured("svc-token-a")).thenThrow(PublicApiException.serviceUnavailable());
        mvc.perform(get("/public/t/shop-a/books/featured")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(post("/public/t/shop-a/books/featured")).andExpect(status().is4xxClientError());
    }
}
