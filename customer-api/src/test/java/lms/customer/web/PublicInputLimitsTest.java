package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.customer.auth.client.AuthClient;
import lms.customer.site.client.SiteClient;
import lms.customer.storefront.StorefrontAccessCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Giới hạn đầu vào ở tầng ứng dụng (THIẾU-4) và đường dẫn chưa có endpoint trả 404 thay vì 401. */
@SpringBootTest
@AutoConfigureMockMvc
class PublicInputLimitsTest {

    @Autowired MockMvc mvc;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void contentLengthQua16KbBi413TruocKhiVaoController() throws Exception {
        String big = "{\"fullName\":\"" + "a".repeat(17 * 1024) + "\"}";
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        verifyNoInteractions(site);
    }

    @Test
    void thanDuoi16KbVanDuocXuLy() throws Exception {
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void jsonLongQua32TangLa400() throws Exception {
        String deep = "{\"a\":".repeat(40) + "1" + "}".repeat(40);
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content(deep))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void duongDanNgoaiDanhSachDongBi401KhongGoiIdentity() throws Exception {
        // Danh sách công khai là danh sách đóng (ADR-0011 2.4): đường dẫn lạ dưới /public/t/{code}/ không mở được.
        for (String path : new String[] {"/public/t/shop-a/books/abc/def/ghi", "/public/t/shop-a/orders/1/cancel",
                "/public/t/shop-a/orders/1/2/3", "/public/t/shop-a/contact-requests"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(storefronts, auth, site);
    }

    @Test
    void duongDanLaKhacVanCan401() throws Exception {
        assertThat(mvc.perform(get("/public/khac")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void headTrenDuongGetCongKhaiKhong401() throws Exception {
        mvc.perform(head("/public/site/pages")).andExpect(status().isOk());
    }
}
