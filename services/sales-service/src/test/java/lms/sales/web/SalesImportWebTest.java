package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.userWithKeys;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportSchema;
import lms.imports.engine.ImportCommitExecutor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.InMemoryImportSessionStore;
import lms.imports.engine.UploadGate;
import lms.imports.web.ImportActorResolver;
import lms.imports.web.ImportExceptionHandler;
import lms.imports.web.JwtImportActorResolver;
import lms.imports.web.MultipartLimitHandler;
import lms.imports.xlsx.StreamingXlsxReader;
import lms.imports.xlsx.SxssfXlsxWriter;
import lms.sales.config.SecurityConfiguration;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StockService;
import lms.sales.stock.application.imports.StockItemImportExecutor;
import lms.sales.stock.application.imports.StockItemImportHandler;
import lms.sales.stock.application.imports.SupplierImportExecutor;
import lms.sales.stock.application.imports.SupplierImportHandler;
import lms.sales.stock.infrastructure.web.StockItemImportController;
import lms.sales.stock.infrastructure.web.SupplierImportController;
import lms.sales.support.Harness;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import lms.tenant.TenantAutoConfiguration;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;

/**
 * ADR-0024 pha 2 (sales): hai loại nhập qua HTTP với kiểm khóa BẬT. Khóa phải theo tên lớp con, chỉ upload và confirm
 * cần khóa, module SALES bắt buộc; luồng upload -> confirm -> kết quả chạy thật trên workflow + bộ giả trong bộ nhớ.
 */
@WebMvcTest({StockItemImportController.class, SupplierImportController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class, SalesImportWebTest.Beans.class, ImportExceptionHandler.class, MultipartLimitHandler.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesImportWebTest {

    static final class FakeCatalog implements CatalogLookupPort {
        final Map<String, CatalogTitle> byIsbn = new HashMap<>();
        volatile boolean down;

        @Override
        public Map<UUID, CatalogTitle> lookup(Collection<UUID> titleIds) {
            return Map.of();
        }

        @Override
        public Map<String, CatalogTitle> lookupByIsbn(Collection<String> isbns) {
            if (down) {
                throw new SalesException(ErrorCode.CATALOG_UNAVAILABLE, "Không gọi được catalog-service");
            }
            Map<String, CatalogTitle> out = new HashMap<>();
            isbns.forEach(i -> {
                if (byIsbn.containsKey(i)) {
                    out.put(i, byIsbn.get(i));
                }
            });
            return out;
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        FakeCatalog fakeCatalog() {
            return new FakeCatalog();
        }

        @Bean(destroyMethod = "close")
        ImportCommitExecutor importCommitExecutor() {
            return new ImportCommitExecutor(4);
        }

        @Bean
        ImportWorkflow importWorkflow(ImportCommitExecutor executor) {
            ImportLimits limits = ImportLimits.defaults();
            return new ImportWorkflow(new InMemoryImportSessionStore(limits, Clock.systemUTC()),
                    new StreamingXlsxReader(limits), new SxssfXlsxWriter(limits), new UploadGate(2), executor, limits,
                    Clock.systemUTC());
        }

        @Bean
        ImportActorResolver importActorResolver() {
            return new JwtImportActorResolver();
        }

        @Bean
        StockItemImportHandler stockItemImportHandler(FakeCatalog catalog, Harness h) {
            return new StockItemImportHandler(catalog, h.stockPort, new StockItemImportExecutor(h.stockPort,
                    new StockService(h.stockPort, h.clock), h.auditPort, h.clock));
        }

        @Bean
        SupplierImportHandler supplierImportHandler(Harness h) {
            return new SupplierImportHandler(h.supplierRepo,
                    new SupplierImportExecutor(h.supplierRepo, h.auditPort, h.clock), null);
        }
    }

    private static final String STOCK = "/internal/imports/stock-items";
    private static final String SUPPLIERS = "/internal/imports/suppliers";
    private static final String STOCK_UPLOAD = "StockItemImportController/upload";
    private static final String STOCK_CONFIRM = "StockItemImportController/confirm";
    private static final String SUP_UPLOAD = "SupplierImportController/upload";
    private static final String SUP_CONFIRM = "SupplierImportController/confirm";

    @Autowired
    MockMvc mvc;

    @Autowired
    Harness h;

    @Autowired
    FakeCatalog catalog;

    @Autowired
    ImportWorkflow workflow;

    @Autowired
    StockItemImportHandler stockHandler;

    @Autowired
    SupplierImportHandler supplierHandler;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    /** Mỗi test một người dùng riêng (instance mới mỗi test) để không đụng giới hạn 3 phiên mở của store dùng chung. */
    private final UUID me = UUID.randomUUID();

    private RequestPostProcessor user(String... keys) {
        return userWithKeys(me, keys);
    }

    private byte[] xlsx(ImportSchema schema, List<String[]> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var spec = schema.sheets().get(0);
            Sheet s = wb.createSheet(spec.name());
            Row head = s.createRow(0);
            for (int c = 0; c < spec.columns().size(); c++) {
                head.createCell(c).setCellValue(spec.columns().get(c).label());
            }
            int r = 1;
            for (String[] data : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < data.length; c++) {
                    if (data[c] != null) {
                        row.createCell(c).setCellValue(data[c]);
                    }
                }
            }
            Sheet meta = wb.createSheet("_meta");
            meta.createRow(0).createCell(0).setCellValue("type");
            meta.getRow(0).createCell(1).setCellValue(schema.type());
            wb.write(out);
            return out.toByteArray();
        }
    }

    private MockMultipartFile file(byte[] data) {
        return new MockMultipartFile("file", "nhap.xlsx", "application/octet-stream", data);
    }

    private JsonNode waitDone(String base, String id, RequestPostProcessor who) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode n = json.readTree(mvc.perform(get(base + "/" + id).with(who)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            String st = n.get("status").asText();
            if (st.equals("COMMITTED") || st.equals("COMMIT_FAILED")) {
                return n;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Phiên không kết thúc");
    }

    @Test
    void mauTaiDuocKhongCanKhoaVaMoiLoaiCoSheetDungTen() throws Exception {
        mvc.perform(get(STOCK + "/template").with(user())).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("mau-stock-items.xlsx")));
        mvc.perform(get(SUPPLIERS + "/template").with(user())).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("mau-suppliers.xlsx")));
        mvc.perform(get(STOCK + "/template")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadVaConfirmCanDungKhoaTheoTenLopCon() throws Exception {
        byte[] data = xlsx(supplierHandler.schema(), List.<String[]>of(new String[] {"NCC Khoa"}));
        mvc.perform(multipart(SUPPLIERS).file(file(data)).with(user())).andExpect(status().isForbidden());
        // khóa của loại khác không thay được
        mvc.perform(multipart(SUPPLIERS).file(file(data)).with(user(STOCK_UPLOAD))).andExpect(status().isForbidden());
        mvc.perform(multipart(SUPPLIERS).file(file(data)).with(user(SUP_UPLOAD))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("suppliers")).andExpect(jsonPath("$.status").value("VALIDATED"));
        String id = uploadSupplier("NCC Khoa 2", user(SUP_UPLOAD));
        // confirm: có khóa upload mà thiếu khóa confirm thì 403
        mvc.perform(post(SUPPLIERS + "/" + id + "/confirm").with(user(SUP_UPLOAD))).andExpect(status().isForbidden());
        // endpoint đọc chỉ cần đăng nhập (kiểm chủ phiên trong workflow)
        mvc.perform(get(SUPPLIERS + "/" + id).with(user())).andExpect(status().isOk());
    }

    private String uploadSupplier(String name, RequestPostProcessor who) throws Exception {
        MvcResult r = mvc.perform(multipart(SUPPLIERS).file(file(xlsx(supplierHandler.schema(),
                List.<String[]>of(new String[] {name})))).with(who)).andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("importId").asText();
    }

    @Test
    void khongCoModuleSalesThiBiChan() throws Exception {
        byte[] data = xlsx(supplierHandler.schema(), List.<String[]>of(new String[] {"NCC Mod"}));
        mvc.perform(multipart(SUPPLIERS).file(file(data)).with(withKeys(TENANT, List.of("LENDING"), SUP_UPLOAD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        mvc.perform(get(SUPPLIERS + "/template").with(withKeys(TENANT, List.of("LENDING")))).andExpect(status().isForbidden());
    }

    @Test
    void nhapMatHangTronVenQuaHttpGhiTonDauKyVaSoCai() throws Exception {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        catalog.byIsbn.put("9780000000011", new CatalogLookupPort.CatalogTitle(t1, "Sách 1", "TG", "9780000000011",
                BigDecimal.valueOf(70_000)));
        catalog.byIsbn.put("9780000000022", new CatalogLookupPort.CatalogTitle(t2, "Sách 2", "TG", "9780000000022",
                BigDecimal.valueOf(30_000)));
        byte[] data = xlsx(stockHandler.schema(), List.of(
                new String[] {"9780000000011", "80000", "2", "web-1", "NXB A", "50000", "K1", "10", "ghi chú", "25"},
                new String[] {"9780000000022"},
                new String[] {"9780000000099"})); // không có trong catalog

        MvcResult up = mvc.perform(multipart(STOCK).file(file(data)).with(user(STOCK_UPLOAD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.totals.valid").value(2)).andExpect(jsonPath("$.totals.invalid").value(1))
                .andReturn();
        String id = json.readTree(up.getResponse().getContentAsString()).get("importId").asText();
        mvc.perform(get(STOCK + "/" + id + "/rows").param("status", "INVALID").with(user()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].issues[0].code").value("TITLE_NOT_FOUND"))
                .andExpect(jsonPath("$.items[0].extra").doesNotExist());
        assertThat(h.item(t1)).as("upload chưa ghi gì").isNull();

        mvc.perform(post(STOCK + "/" + id + "/confirm").with(user(STOCK_UPLOAD))).andExpect(status().isForbidden());
        mvc.perform(post(STOCK + "/" + id + "/confirm").with(user(STOCK_CONFIRM))).andExpect(status().is2xxSuccessful());

        JsonNode done = waitDone(STOCK, id, user());
        assertThat(done.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(done.get("result").get("created").asInt()).isEqualTo(2);
        assertThat(h.item(t1).onHand()).isEqualTo(25);
        assertThat(h.item(t1).details().sku()).isEqualTo("WEB-1");
        assertThat(h.item(t2).onHand()).isZero();
        assertThat(h.ledger()).filteredOn(m -> m.titleId().equals(t1)).singleElement().satisfies(m -> {
            assertThat(m.type().name()).isEqualTo("ADJUSTMENT");
            assertThat(m.deltaOnHand()).isEqualTo(25);
        });
        assertThat(h.audits()).as("một audit tóm tắt cho phiên này").filteredOn(a -> a.action().equals("STOCK_ITEMS_IMPORTED")
                && a.aggregateId().toString().equals(id)).hasSize(1);
        // confirm lặp lại không ghi lại
        mvc.perform(post(STOCK + "/" + id + "/confirm").with(user(STOCK_CONFIRM))).andExpect(status().isOk());
        assertThat(h.item(t1).onHand()).isEqualTo(25);
        assertThat(h.ledger()).filteredOn(m -> m.titleId().equals(t1)).hasSize(1);
        // file lỗi tải được
        mvc.perform(get(STOCK + "/" + id + "/error-file").with(user())).andExpect(status().isOk());
    }

    @Test
    void catalogKhongKhaDungThiUploadTraLoiRo() throws Exception {
        catalog.byIsbn.put("9780000000033", new CatalogLookupPort.CatalogTitle(UUID.randomUUID(), "S", "T", "9780000000033",
                BigDecimal.ONE));
        catalog.down = true;
        try {
            mvc.perform(multipart(STOCK).file(file(xlsx(stockHandler.schema(), List.<String[]>of(new String[] {"9780000000033"}))))
                            .with(user(STOCK_UPLOAD)))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
        } finally {
            catalog.down = false;
        }
    }

    @Test
    void nhapNhaCungCapVaPhienChiCuaChuPhien() throws Exception {
        h.supplierRepo.save(lms.sales.stock.domain.Supplier.create(h.tenant, "Có Sẵn", null, null, null, null));
        byte[] data = xlsx(supplierHandler.schema(), List.<String[]>of(new String[] {"Mới 1", "0901"}, new String[] {"có sẵn"},
                new String[] {"MỚI 1"}));
        MvcResult up = mvc.perform(multipart(SUPPLIERS).file(file(data)).with(user(SUP_UPLOAD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totals.valid").value(1))
                .andExpect(jsonPath("$.totals.duplicate").value(1)).andExpect(jsonPath("$.totals.invalid").value(1)).andReturn();
        String id = json.readTree(up.getResponse().getContentAsString()).get("importId").asText();

        // người khác (sub khác) không thấy phiên
        RequestPostProcessor other = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                        .claim("modules", List.of("SALES")));
        mvc.perform(get(SUPPLIERS + "/" + id).with(other)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));

        mvc.perform(post(SUPPLIERS + "/" + id + "/confirm").with(user(SUP_CONFIRM))).andExpect(status().is2xxSuccessful());
        JsonNode done = waitDone(SUPPLIERS, id, user());
        assertThat(done.get("result").get("created").asInt()).isEqualTo(1);
        assertThat(h.supplierRepo.existsByName(h.tenant, "mới 1", null)).isTrue();
    }

    @Test
    void khoaSinhRaDungTenLopConVaMethod() throws Exception {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        byte[] data = xlsx(stockHandler.schema(), List.<String[]>of(new String[] {"9780000000044"}));
        MvcResult up = mvc.perform(multipart(STOCK).file(file(data)).with(admin())).andReturn();
        MvcResult confirm = mvc.perform(post(STOCK + "/" + UUID.randomUUID() + "/confirm").with(admin())).andReturn();
        assertThat(resolver.resolve((HandlerMethod) up.getHandler()).key()).isEqualTo(STOCK_UPLOAD);
        assertThat(resolver.resolve((HandlerMethod) confirm.getHandler()).key()).isEqualTo(STOCK_CONFIRM);
        MvcResult sup = mvc.perform(multipart(SUPPLIERS).file(file(xlsx(supplierHandler.schema(),
                List.<String[]>of(new String[] {"Khóa NCC"})))).with(admin())).andReturn();
        assertThat(resolver.resolve((HandlerMethod) sup.getHandler()).key()).isEqualTo(SUP_UPLOAD);
        List<String> keys = new ArrayList<>(List.of(STOCK_UPLOAD, STOCK_CONFIRM, SUP_UPLOAD, SUP_CONFIRM));
        assertThat(keys).hasSize(4);
    }
}
