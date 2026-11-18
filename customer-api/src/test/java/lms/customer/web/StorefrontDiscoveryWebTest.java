package lms.customer.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.customer.banner.BannerCache;
import lms.customer.banner.client.BannerClient;
import lms.customer.banner.dto.BannerDtos.BannerView;
import lms.customer.banner.web.BannerController;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.SuggestionView;
import lms.customer.catalog.web.BookController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ADR-0019 B4: sort/inStock/suggestions/banners, 400 trước khi gọi sales, Cache-Control, route công khai. */
@WebMvcTest({BookController.class, BannerController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class, BannerCache.class})
class StorefrontDiscoveryWebTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean CatalogClient catalog;
    @MockitoBean BannerClient banners;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void shopA() {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(tenantA, "shop-a", "Hiệu sách A", "svc-token-a", 300));
    }

    @Test
    void sortVaInStockHopLeDuocGuiNguyenSangSales() throws Exception {
        when(catalog.list(anyString(), any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(new BookPageView(List.of(), 0, 10, 0));
        for (String sort : List.of("newest", "price_asc", "price_desc")) {
            mvc.perform(get("/public/t/shop-a/books").param("sort", sort).param("inStock", "true").param("size", "10"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=30, public"));
            verify(catalog).list("svc-token-a", null, sort, true, null, 0, 10);
        }
    }

    @Test
    void sortHoacInStockSaiLa400KhongGoiSalesKhongPhanGiaiCuaHang() throws Exception {
        for (String query : List.of("sort=PRICE_ASC", "sort=price", "sort=", "sort=newest,price_asc", "sort=name",
                "inStock=false", "inStock=1", "inStock=yes", "inStock=", "inStock=TRUE",
                "sort=oldest&inStock=true")) {
            mvc.perform(get("/public/t/shop-a/books?" + query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(catalog, storefronts);
    }

    @Test
    void groupHopLeDuocGuiNguyenSangSalesVaVangThiKhongGui() throws Exception {
        when(catalog.list(anyString(), any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(new BookPageView(List.of(), 0, 10, 0));
        mvc.perform(get("/public/t/shop-a/books?group=WORK&size=10")).andExpect(status().isOk());
        verify(catalog).list("svc-token-a", null, null, false, "WORK", 0, 10);
        mvc.perform(get("/public/t/shop-a/books?group=EDITION&size=10")).andExpect(status().isOk());
        verify(catalog).list("svc-token-a", null, null, false, "EDITION", 0, 10);
    }

    @Test
    void groupSaiLa400KhongGoiSalesKhongPhanGiaiCuaHang() throws Exception {
        for (String query : List.of("group=work", "group=Work", "group=", "group=WORK,EDITION", "group=SERIES",
                "group=WORK%20")) {
            mvc.perform(get("/public/t/shop-a/books?" + query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(catalog, storefronts);
    }

    @Test
    void sizeToiDa50() throws Exception {
        when(catalog.list(anyString(), any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(new BookPageView(List.of(), 0, 50, 0));
        mvc.perform(get("/public/t/shop-a/books?size=999&sort=newest")).andExpect(status().isOk());
        verify(catalog).list("svc-token-a", null, "newest", false, null, 0, 50);
    }

    @Test
    void goiYTraBaTruongVaCache15Giay() throws Exception {
        when(catalog.suggestions(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(new SuggestionView(bookId, "Nhà giả kim", "Paulo Coelho")));
        mvc.perform(get("/public/t/shop-a/books/suggestions").param("q", "  nhà  "))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=15, public"))
                .andExpect(jsonPath("$[0].bookRef").value(bookId.toString()))
                .andExpect(jsonPath("$[0].title").value("Nhà giả kim"))
                .andExpect(jsonPath("$[0].author").value("Paulo Coelho"))
                .andExpect(jsonPath("$[0].price").doesNotExist());
        verify(catalog).suggestions("svc-token-a", "nhà", 5);
        // route literal không bị bắt nhầm bởi /books/{bookRef}: không có lời gọi get()
        mvc.perform(head("/public/t/shop-a/books/suggestions").param("q", "nhà")).andExpect(status().isOk());
    }

    @Test
    void limitDuocChan() throws Exception {
        when(catalog.suggestions(anyString(), anyString(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/public/t/shop-a/books/suggestions?q=ab&limit=99")).andExpect(status().isOk());
        mvc.perform(get("/public/t/shop-a/books/suggestions?q=ab&limit=0")).andExpect(status().isOk());
        mvc.perform(get("/public/t/shop-a/books/suggestions?q=ab&limit=3")).andExpect(status().isOk());
        verify(catalog).suggestions("svc-token-a", "ab", 8);
        verify(catalog).suggestions("svc-token-a", "ab", 5);
        verify(catalog).suggestions("svc-token-a", "ab", 3);
    }

    @Test
    void goiYQSaiLa400KhongGoiSales() throws Exception {
        // MockMvc mã hóa lại "%" trong chuỗi URI (q=%20a%20 thành chữ "%20a%20"), nên truyền giá trị đã giải mã bằng param()
        String uri = "/public/t/shop-a/books/suggestions";
        mvc.perform(get(uri)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String q : List.of("", "a", " a ", "a".repeat(51), "ab\ncd")) {
            mvc.perform(get(uri).param("q", q)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(catalog, storefronts);
    }

    @Test
    void goiYChoPhep50KyTuVa2KyTu() throws Exception {
        when(catalog.suggestions(anyString(), anyString(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/public/t/shop-a/books/suggestions?q=" + "a".repeat(50))).andExpect(status().isOk());
        mvc.perform(get("/public/t/shop-a/books/suggestions?q=ab")).andExpect(status().isOk());
    }

    @Test
    void bannerTraDanhSachCache60VaDemServerChiGoiSalesMotLan() throws Exception {
        UUID bid = UUID.randomUUID();
        when(banners.list("svc-token-a")).thenReturn(List.of(new BannerView(bid, "Giảm giá", null, "/books", 0)));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/public/t/shop-a/banners"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=60, public"))
                    .andExpect(jsonPath("$[0].id").value(bid.toString()))
                    .andExpect(jsonPath("$[0].title").value("Giảm giá"))
                    .andExpect(jsonPath("$[0].linkPath").value("/books"))
                    .andExpect(jsonPath("$[0].tenantId").doesNotExist());
        }
        verify(banners, times(1)).list("svc-token-a");
        mvc.perform(head("/public/t/shop-a/banners")).andExpect(status().isOk());
    }

    @Test
    void bannerCuaHangKhongKhaDungLa404KhongGoiSales() throws Exception {
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(get("/public/t/khong-co/banners")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(banners);
    }

    @Test
    void bannerLoiSalesLa503VaKhongBiDem() throws Exception {
        UUID other = UUID.randomUUID();
        when(storefronts.resolve("shop-b")).thenReturn(new StorefrontAccess(other, "shop-b", "B", "svc-b", 300));
        when(banners.list("svc-b")).thenThrow(PublicApiException.serviceUnavailable())
                .thenReturn(List.of());
        mvc.perform(get("/public/t/shop-b/banners")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(get("/public/t/shop-b/banners")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        verify(banners, times(2)).list("svc-b");
    }

    @Test
    void bannerChiChoPhepGetHead() throws Exception {
        int status = mvc.perform(post("/public/t/shop-a/banners").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isBetween(400, 499);
        verifyNoInteractions(banners);
    }
}
