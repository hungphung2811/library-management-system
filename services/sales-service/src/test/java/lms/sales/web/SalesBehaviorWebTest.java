package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.USER;
import static lms.sales.support.WebTokens.withKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MvcResult;

/** Hành vi HTTP của các controller với nghiệp vụ thật (tầng lưu trữ và catalog giả): trạng thái, Location, mã lỗi. */
@WebMvcTest({SalesSettingsController.class, StockItemController.class, StockAdjustmentController.class,
        SupplierController.class, GoodsReceiptController.class, PosOrderController.class, SalesOrderController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesBehaviorWebTest {

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

    /** Đưa một đầu sách mới vào catalog giả, đăng ký bán và nhập tồn qua nghiệp vụ thật (tenant của token). */
    private UUID book(long coverPrice, Long sellingPrice, long stock) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach " + id.toString().substring(0, 6),
                "Tac gia", "ISBN-" + id.toString().substring(0, 8), BigDecimal.valueOf(coverPrice)));
        h.registerItem.execute(TENANT, USER, id, sellingPrice, null);
        if (stock > 0) {
            h.adjust.execute(TENANT, USER, id, stock, "ton dau");
        }
        return id;
    }

    private static String pos(UUID title, int qty, String extra, UUID requestId) {
        return "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + (extra == null ? "" : "," + extra)
                + "}],\"paymentMethod\":\"CASH\",\"clientRequestId\":\"" + requestId + "\"}";
    }

    @Test
    void posCreateTra201KemLocationVaDonDaChe() throws Exception {
        UUID title = book(100_000, null, 5);
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":2}],\"paymentMethod\":\"CASH\","
                + "\"walkIn\":{\"name\":\"Nguyen Van An\",\"phone\":\"0912345678\"},\"clientRequestId\":\""
                + UUID.randomUUID() + "\"}";
        MvcResult r = mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith(B + "/orders/")))
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.paymentIntent.provider").value("MANUAL"))
                .andExpect(jsonPath("$.fulfilment").value("COUNTER"))
                .andExpect(jsonPath("$.grandTotal").value(200_000))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andReturn();
        String response = r.getResponse().getContentAsString();
        // Liên hệ đã che, không lộ tên/điện thoại đầy đủ
        assertThat(response).doesNotContain("Nguyen Van An").doesNotContain("0912345678");
        JsonNode order = json.readTree(response);
        // ADR-0021: hai bước. Chờ thu: tồn mới giữ, chưa có phiếu thu; thu ngân xác nhận thì xuất kho và có phiếu
        assertThat(h.item(title).onHand()).isEqualTo(5);
        assertThat(h.item(title).reserved()).isEqualTo(2);
        mvc.perform(get(B + "/orders/" + order.get("id").asText() + "/receipt")
                        .with(withKey("SalesOrderController/receipt")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAID"));
        h.intentUseCases.confirm(TENANT, USER, UUID.fromString(order.get("paymentIntent").get("id").asText()),
                new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(null, null));
        assertThat(h.item(title).onHand()).isEqualTo(3);
        assertThat(h.item(title).reserved()).isEqualTo(0);

        // Đọc chi tiết vẫn che; liên hệ đầy đủ chỉ qua getContact
        mvc.perform(get(B + "/orders/" + order.get("id").asText()).with(withKey("SalesOrderController/get")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact.name").value(org.hamcrest.Matchers.not("Nguyen Van An")));
        mvc.perform(get(B + "/orders/" + order.get("id").asText() + "/contact")
                        .with(withKey("SalesOrderController/getContact")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nguyen Van An"))
                .andExpect(jsonPath("$.phone").value("0912345678"));
        mvc.perform(get(B + "/orders/" + order.get("id").asText() + "/receipt")
                        .with(withKey("SalesOrderController/receipt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(200_000))
                .andExpect(jsonPath("$.orderNo").value(order.get("orderNo").asText()));
    }

    @Test
    void posCreateCoGiamGiaBi400DiscountNotAllowedVaKhongTruTon() throws Exception {
        UUID title = book(100_000, null, 5);
        mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pos(title, 1, "\"discount\":{\"type\":\"AMOUNT\",\"value\":1000}", UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCOUNT_NOT_ALLOWED"));
        mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":1}],"
                                + "\"paymentMethod\":\"CASH\",\"orderDiscount\":{\"type\":\"PERCENT\",\"value\":500},"
                                + "\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCOUNT_NOT_ALLOWED"));
        assertThat(h.item(title).onHand()).isEqualTo(5);
    }

    @Test
    void createWithDiscountCanKhoaRiengVaApDungGiamGia() throws Exception {
        UUID title = book(100_000, null, 5);
        String body = pos(title, 1, "\"discount\":{\"type\":\"PERCENT\",\"value\":1000}", UUID.randomUUID());
        // khóa của create không đủ cho createWithDiscount
        mvc.perform(post(B + "/pos-orders/discounted").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(B + "/pos-orders/discounted").with(withKey("PosOrderController/createWithDiscount"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountTotal").value(10_000))
                .andExpect(jsonPath("$.grandTotal").value(90_000));
    }

    @Test
    void vuotTonTra409KemChiTietVaGuiLaiCungClientRequestIdKhongTruHaiLan() throws Exception {
        UUID title = book(50_000, null, 1);
        UUID request = UUID.randomUUID();
        String ok = pos(title, 1, null, request);
        mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                .contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isCreated());
        // gửi lại cùng clientRequestId: trả đơn cũ, tồn không đổi
        mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isCreated());
        assertThat(h.item(title).reserved()).isEqualTo(1); // hai bước: giữ tồn một lần dù gửi lại
        // đơn khác hết hàng
        mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(pos(title, 1, null, UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details", hasItem("titleId=" + title)))
                .andExpect(jsonPath("$.details", hasItem("available=0")));
    }

    @Test
    void clientRequestIdKhongPhaiUuidVanIdempotent() throws Exception {
        UUID title = book(50_000, null, 5);
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":1}],\"paymentMethod\":\"CARD\","
                + "\"clientRequestId\":\"order-click-20261117-0001\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        }
        assertThat(h.item(title).reserved()).isEqualTo(1); // giữ một lần dù gửi lại (idempotent)
    }

    @Test
    void validateBodyTra400ValidationFailed() throws Exception {
        UUID title = book(50_000, null, 5);
        for (String bad : new String[] {
                "{\"lines\":[],\"paymentMethod\":\"CASH\",\"clientRequestId\":\"x\"}",
                "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":0}],\"paymentMethod\":\"CASH\","
                        + "\"clientRequestId\":\"x\"}",
                "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":1}],\"paymentMethod\":\"BITCOIN\","
                        + "\"clientRequestId\":\"x\"}",
                "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":1}],\"clientRequestId\":\"x\"}",
                "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":1}],\"paymentMethod\":\"CASH\"}"}) {
            mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        // COD không dùng được ở POS: lỗi nghiệp vụ INVALID_REQUEST
        mvc.perform(post(B + "/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":1}],"
                                + "\"paymentMethod\":\"COD\",\"clientRequestId\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void donKhongTonTaiTra404OrderNotFound() throws Exception {
        mvc.perform(get(B + "/orders/" + UUID.randomUUID()).with(withKey("SalesOrderController/get")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void registerTra201KemLocationVaTrung409() throws Exception {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Dang ky", "TG", "I-" + id,
                BigDecimal.valueOf(70_000)));
        String body = "{\"titleId\":\"" + id + "\",\"sellingPrice\":80000}";
        mvc.perform(post(B + "/stock-items").with(withKey("StockItemController/register"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", B + "/stock-items/" + id))
                .andExpect(jsonPath("$.onHand").value(0))
                .andExpect(jsonPath("$.effectivePrice").value(80_000));
        mvc.perform(post(B + "/stock-items").with(withKey("StockItemController/register"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_ITEM_EXISTS"));
        mvc.perform(post(B + "/stock-items").with(withKey("StockItemController/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TITLE_NOT_AVAILABLE"));
        // đổi giá: null quay về giá bìa
        mvc.perform(put(B + "/stock-items/" + id + "/price").with(withKey("StockItemController/updatePrice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sellingPrice\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.effectivePrice").value(70_000));
    }

    @Test
    void nhapKhoXacNhanTangTonVaSoCai() throws Exception {
        UUID title = book(60_000, null, 0);
        MvcResult supplier = mvc.perform(post(B + "/suppliers").with(withKey("SupplierController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"NCC " + title + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String supplierId = json.readTree(supplier.getResponse().getContentAsString()).get("id").asText();
        String create = "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"titleId\":\"" + title
                + "\",\"quantity\":10,\"unitCost\":30000}],\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        MvcResult receipt = mvc.perform(post(B + "/goods-receipts").with(withKey("GoodsReceiptController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalCost").value(300_000)).andReturn();
        String id = json.readTree(receipt.getResponse().getContentAsString()).get("id").asText();
        assertThat(h.item(title).onHand()).isZero();

        mvc.perform(post(B + "/goods-receipts/" + id + "/confirmation")
                        .with(withKey("GoodsReceiptController/confirm")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(h.item(title).onHand()).isEqualTo(10);
        mvc.perform(get(B + "/stock-items/" + title + "/ledger").with(withKey("StockItemController/ledger")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").exists());
        // xác nhận lần hai và hủy phiếu đã xác nhận: 409
        mvc.perform(post(B + "/goods-receipts/" + id + "/confirmation")
                        .with(withKey("GoodsReceiptController/confirm")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECEIPT_STATE_CONFLICT"));
        mvc.perform(post(B + "/goods-receipts/" + id + "/cancellation")
                        .with(withKey("GoodsReceiptController/cancel")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECEIPT_STATE_CONFLICT"));
        assertThat(h.item(title).onHand()).isEqualTo(10);
    }

    @Test
    void dieuChinhVaKiemKhoBatBuocLyDo() throws Exception {
        UUID title = book(60_000, null, 5);
        mvc.perform(post(B + "/stock-adjustments").with(withKey("StockAdjustmentController/adjust"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + title + "\",\"delta\":-2,\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(B + "/stock-adjustments").with(withKey("StockAdjustmentController/adjust"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + title + "\",\"delta\":-2,\"reason\":\"hu hong\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onHand").value(3));
        mvc.perform(post(B + "/stock-adjustments").with(withKey("StockAdjustmentController/adjust"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + title + "\",\"delta\":-9,\"reason\":\"qua tay\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mvc.perform(post(B + "/stocktakes").with(withKey("StockAdjustmentController/stocktake"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + title + "\",\"countedOnHand\":7,\"reason\":\"kiem ky\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onHand").value(7));
    }

    @Test
    void cauHinhLayMacDinhVaCapNhatTheoVersion() throws Exception {
        // Harness dùng chung nên chỉ kiểm cấu trúc: có thể đã được test khác lưu
        MvcResult get = mvc.perform(get(B + "/settings").with(withKey("SalesSettingsController/get")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("VND")).andReturn();
        long version = json.readTree(get.getResponse().getContentAsString()).get("version").asLong();
        String body = "{\"vatRateBps\":800,\"pricesIncludeVat\":false,\"returnWindowDays\":10,"
                + "\"fixedShippingFee\":15000,\"lowStockThreshold\":3,\"walkInPiiRetentionMonths\":6,\"version\":"
                + version + "}";
        mvc.perform(put(B + "/settings").with(withKey("SalesSettingsController/update"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vatRateBps").value(800));
        mvc.perform(put(B + "/settings").with(withKey("SalesSettingsController/update"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));
        mvc.perform(put(B + "/settings").with(withKey("SalesSettingsController/update"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"vatRateBps\":800", "\"vatRateBps\":20000")
                                .replace("\"version\":" + version, "\"version\":" + (version + 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SETTINGS"));
    }

    @Test
    void phanTrangMacDinhVaIdKhongPhaiUuidTra400() throws Exception {
        mvc.perform(get(B + "/orders").with(withKey("SalesOrderController/search")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").exists());
        mvc.perform(get(B + "/orders/khong-phai-uuid").with(withKey("SalesOrderController/get")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(B + "/orders").param("status", "NOPE").with(withKey("SalesOrderController/search")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
