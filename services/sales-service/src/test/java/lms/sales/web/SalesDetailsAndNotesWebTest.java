package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.USER;
import static lms.sales.support.WebTokens.withKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.infrastructure.web.PosOrderController;
import lms.sales.ordering.infrastructure.web.SalesOrderController;
import lms.sales.settings.infrastructure.web.SalesSettingsController;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.infrastructure.web.GoodsReceiptController;
import lms.sales.stock.infrastructure.web.StockAdjustmentController;
import lms.sales.stock.infrastructure.web.StockItemController;
import lms.sales.stock.infrastructure.web.SupplierController;
import lms.sales.support.Harness;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Chi tiết quản lý mặt hàng và ghi chú đơn qua HTTP: dữ liệu nội bộ (costPrice, note, staffNote) chỉ có ở endpoint khóa
 * riêng, không lộ ở get/search; khóa khác bị 403; validation và SKU trùng.
 */
@WebMvcTest({SalesSettingsController.class, StockItemController.class, StockAdjustmentController.class,
        SupplierController.class, GoodsReceiptController.class, PosOrderController.class, SalesOrderController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesDetailsAndNotesWebTest {

    private static final String B = "/internal/sales";

    @Autowired
    MockMvc mvc;

    @Autowired
    Harness h;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private UUID book(long coverPrice, long stock) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach " + id.toString().substring(0, 6),
                "Tac gia", "ISBN-" + id.toString().substring(0, 8), BigDecimal.valueOf(coverPrice)));
        h.registerItem.execute(TENANT, USER, id, null, null);
        if (stock > 0) {
            h.adjust.execute(TENANT, USER, id, stock, "ton dau");
        }
        return id;
    }

    private static String text(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void chiTietNoiBoChiCoODetailsVaKhongLoOGetVaSearch() throws Exception {
        UUID title = book(100_000, 3);
        mvc.perform(put(B + "/stock-items/" + title + "/details").with(withKey("StockItemController/updateDetails"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\" lms-2024-0007 \",\"supplier\":\"Fahasa\",\"costPrice\":62000,"
                                + "\"binLocation\":\"K1-A3\",\"reorderQuantity\":20,"
                                + "\"lastRestockedAt\":\"2026-11-10T02:00:00Z\",\"note\":\"Bìa dễ trầy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("LMS-2024-0007"))
                .andExpect(jsonPath("$.costPrice").value(62_000))
                .andExpect(jsonPath("$.note").value("Bìa dễ trầy"))
                .andExpect(jsonPath("$.lastRestockedAt").value("2026-11-10T02:00:00Z"));

        String one = text(mvc.perform(get(B + "/stock-items/" + title).with(withKey("StockItemController/get")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("LMS-2024-0007"))
                .andExpect(jsonPath("$.supplier").value("Fahasa"))
                .andExpect(jsonPath("$.binLocation").value("K1-A3"))
                .andExpect(jsonPath("$.reorderQuantity").value(20))
                .andExpect(jsonPath("$.lastRestockedAt").value("2026-11-10T02:00:00Z")));
        assertThat(json.readTree(one).has("costPrice")).isFalse();
        assertThat(json.readTree(one).has("note")).isFalse();
        assertThat(one).doesNotContain("62000").doesNotContain("Bìa dễ trầy");

        String list = text(mvc.perform(get(B + "/stock-items").with(withKey("StockItemController/search")))
                .andExpect(status().isOk()));
        assertThat(list).doesNotContain("costPrice").doesNotContain("62000").doesNotContain("Bìa dễ trầy");

        mvc.perform(get(B + "/stock-items/" + title + "/details").with(withKey("StockItemController/getDetails")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.costPrice").value(62_000));
        // khóa get/search (thu ngân) không xem hay sửa được chi tiết nội bộ
        mvc.perform(get(B + "/stock-items/" + title + "/details").with(withKey("StockItemController/get")))
                .andExpect(status().isForbidden());
        mvc.perform(put(B + "/stock-items/" + title + "/details").with(withKey("StockItemController/get"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void validationVaSkuTrung() throws Exception {
        UUID a = book(100_000, 0);
        UUID b = book(100_000, 0);
        var key = withKey("StockItemController/updateDetails");
        mvc.perform(put(B + "/stock-items/" + a + "/details").with(key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"" + "x".repeat(41) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(B + "/stock-items/" + a + "/details").with(key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"costPrice\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(put(B + "/stock-items/" + a + "/details").with(key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reorderQuantity\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(put(B + "/stock-items/" + a + "/details").with(key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(B + "/stock-items/" + a + "/details").with(key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"DUP-1\"}")).andExpect(status().isOk());
        mvc.perform(put(B + "/stock-items/" + b + "/details").with(key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"dup-1\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_EXISTS"));
        mvc.perform(put(B + "/stock-items/" + UUID.randomUUID() + "/details").with(key)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    }

    @Test
    void dangKyMatHangNhanChiTietTuyChon() throws Exception {
        UUID title = UUID.randomUUID();
        h.catalogTitles.put(title, new CatalogLookupPort.CatalogTitle(title, "S", "T", "I", BigDecimal.TEN));
        mvc.perform(post(B + "/stock-items").with(withKey("StockItemController/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + title + "\",\"sku\":\"LMS-1\",\"supplier\":\"Nhã Nam\","
                                + "\"costPrice\":5000,\"binLocation\":\"K2-B1\",\"reorderQuantity\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("LMS-1"))
                .andExpect(jsonPath("$.supplier").value("Nhã Nam"))
                .andExpect(jsonPath("$.binLocation").value("K2-B1"))
                .andExpect(jsonPath("$.costPrice").doesNotExist());
        assertThat(h.item(title).details().costPrice().amount()).isEqualTo(5_000);
    }

    @Test
    void staffNoteChiCoODonNotesKhongLoOGetVaSearch() throws Exception {
        UUID title = book(100_000, 5);
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":1}],\"paymentMethod\":\"CASH\","
                + "\"customerNote\":\"Bọc bìa giúp mình\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        JsonNode order = json.readTree(text(mvc.perform(post(B + "/pos-orders")
                        .with(withKey("PosOrderController/create")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerNote").value("Bọc bìa giúp mình"))
                .andExpect(jsonPath("$.staffNote").doesNotExist())));
        String id = order.get("id").asText();

        // POS (COUNTER) không có ngày giao dự kiến
        mvc.perform(put(B + "/orders/" + id + "/notes").with(withKey("SalesOrderController/updateNotes"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffNote\":\"Khách quen\",\"expectedDeliveryDate\":\"2026-12-01\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(B + "/orders/" + id + "/notes").with(withKey("SalesOrderController/updateNotes"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"staffNote\":\"Khách quen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffNote").value("Khách quen"))
                .andExpect(jsonPath("$.customerNote").value("Bọc bìa giúp mình"));
        mvc.perform(put(B + "/orders/" + id + "/notes").with(withKey("SalesOrderController/updateNotes"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffNote\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());

        String detail = text(mvc.perform(get(B + "/orders/" + id).with(withKey("SalesOrderController/get")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerNote").value("Bọc bìa giúp mình")));
        String list = text(mvc.perform(get(B + "/orders").with(withKey("SalesOrderController/search")))
                .andExpect(status().isOk()));
        assertThat(detail).doesNotContain("staffNote").doesNotContain("Khách quen");
        assertThat(list).doesNotContain("staffNote").doesNotContain("Khách quen");

        mvc.perform(get(B + "/orders/" + id + "/notes").with(withKey("SalesOrderController/getNotes")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.staffNote").value("Khách quen"));
        mvc.perform(get(B + "/orders/" + id + "/notes").with(withKey("SalesOrderController/get")))
                .andExpect(status().isForbidden());
    }
}
