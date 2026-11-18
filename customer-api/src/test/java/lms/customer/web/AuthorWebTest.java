package lms.customer.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.customer.author.FeaturedAuthors;
import lms.customer.author.dto.AuthorDtos.AuthorView;
import lms.customer.author.web.AuthorController;
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

/** GET /public/t/{code}/authors/featured: công khai, chỉ trường name, Cache-Control, 404 chung, route ngoài danh sách bị chặn. */
@WebMvcTest(AuthorController.class)
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class})
class AuthorWebTest {

    @Autowired MockMvc mvc;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean FeaturedAuthors featured;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void shopA() {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(UUID.randomUUID(), "shop-a", "Hiệu sách A", "svc-token-a", 300));
    }

    @Test
    void congKhaiKhongCanTokenChiCoTruongNameVaCache60() throws Exception {
        when(featured.featured("svc-token-a")).thenReturn(List.of(new AuthorView("An"), new AuthorView("Bình")));
        mvc.perform(get("/public/t/shop-a/authors/featured").header(HttpHeaders.AUTHORIZATION, "Bearer rac.khong.hop.le"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("An"))
                .andExpect(jsonPath("$[0].length()").value(1)).andExpect(jsonPath("$[1].name").value("Bình"));
    }

    @Test
    void cuaHangKhongKhaDungLa404KhongGoiCatalogHayInfra() throws Exception {
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(get("/public/t/khong-co/authors/featured")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(featured);
    }

    @Test
    void loiDownstreamThanh503Chung() throws Exception {
        when(featured.featured("svc-token-a")).thenThrow(PublicApiException.serviceUnavailable());
        mvc.perform(get("/public/t/shop-a/authors/featured")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void routeNgoaiDanhSachVanBiChan() throws Exception {
        mvc.perform(post("/public/t/shop-a/authors/featured")).andExpect(status().is4xxClientError());
        mvc.perform(get("/public/t/shop-a/authors")).andExpect(status().isUnauthorized());
        mvc.perform(get("/public/t/shop-a/authors/featured/x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/public/t/shop-a/authors/other")).andExpect(status().isUnauthorized());
        verifyNoInteractions(featured);
    }
}
