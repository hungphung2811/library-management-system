package lms.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import lms.api.catalog.dto.ReferenceView;
import lms.api.catalog.dto.SaveReferenceRequest;
import lms.api.catalog.dto.SaveShelfLocationRequest;
import lms.api.catalog.dto.ShelfLocationView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import lms.api.catalog.client.http.HttpReferenceCatalogClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Thông tin bổ sung của tác giả, nhà xuất bản, vị trí kệ trong DTO lms-api (V8). */
class ReferenceExtraFieldsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void referenceViewChiXuatTruongBoSungKhiCoGiaTri() throws Exception {
        UUID id = UUID.randomUUID();
        JsonNode category = json.readTree(json.writeValueAsString(new ReferenceView(id, "Văn học", null, null, null, true, null)));
        assertThat(category.has("birthYear")).isFalse();
        assertThat(category.has("country")).isFalse();
        assertThat(category.has("parentId")).isTrue();

        JsonNode author = json.readTree(json.writeValueAsString(new ReferenceView(id, "Hawking", null, null, null, true,
                null, 1942, 2018, "Anh", "https://x.example/p.jpg", "https://hawking.example", null, null, null)));
        assertThat(author.get("birthYear").asInt()).isEqualTo(1942);
        assertThat(author.get("deathYear").asInt()).isEqualTo(2018);
        assertThat(author.get("nationality").asText()).isEqualTo("Anh");
        assertThat(author.get("photoUrl").asText()).isEqualTo("https://x.example/p.jpg");
        assertThat(author.get("website").asText()).isEqualTo("https://hawking.example");
        assertThat(author.has("country")).isFalse();

        JsonNode publisher = json.readTree(json.writeValueAsString(new ReferenceView(id, "NXB Trẻ", null, null, null,
                true, null, null, null, null, null, "https://nxbtre.example", "Việt Nam", 1981, "TP.HCM")));
        assertThat(publisher.get("country").asText()).isEqualTo("Việt Nam");
        assertThat(publisher.get("foundedYear").asInt()).isEqualTo(1981);
        assertThat(publisher.get("address").asText()).isEqualTo("TP.HCM");
        assertThat(publisher.has("birthYear")).isFalse();
    }

    @Test
    void saveReferenceRequestKiemRangBuoc() {
        assertThat(validator.validate(new SaveReferenceRequest("A", null, null, 1900, 1950, "Pháp",
                "https://x.example/a", "http://x.example", "Việt Nam", 1957, "Hà Nội"))).isEmpty();
        assertThat(validator.validate(new SaveReferenceRequest("A", null, null))).isEmpty();
        for (SaveReferenceRequest bad : new SaveReferenceRequest[] {
                new SaveReferenceRequest("A", null, null, 0, null, null, null, null, null, null, null),
                new SaveReferenceRequest("A", null, null, null, 3001, null, null, null, null, null, null),
                new SaveReferenceRequest("A", null, null, null, null, "a".repeat(101), null, null, null, null, null),
                new SaveReferenceRequest("A", null, null, null, null, null, "ftp://x", null, null, null, null),
                new SaveReferenceRequest("A", null, null, null, null, null, null, "javascript:alert(1)", null, null, null),
                new SaveReferenceRequest("A", null, null, null, null, null, null, null, "a".repeat(101), null, null),
                new SaveReferenceRequest("A", null, null, null, null, null, null, null, null, 1399, null),
                new SaveReferenceRequest("A", null, null, null, null, null, null, null, null, null, "a".repeat(501))}) {
            assertThat(validator.validate(bad)).isNotEmpty();
        }
    }

    @Test
    void saveShelfLocationRequestKiemRangBuoc() {
        assertThat(validator.validate(new SaveShelfLocationRequest("K-01", null, "Tầng 2", "Khu Văn học", 100000))).isEmpty();
        assertThat(validator.validate(new SaveShelfLocationRequest("K-01", null))).isEmpty();
        assertThat(validator.validate(new SaveShelfLocationRequest("K-01", null, null, null, 0))).isNotEmpty();
        assertThat(validator.validate(new SaveShelfLocationRequest("K-01", null, null, null, 100001))).isNotEmpty();
        assertThat(validator.validate(new SaveShelfLocationRequest("K-01", null, "a".repeat(21), null, null))).isNotEmpty();
        assertThat(validator.validate(new SaveShelfLocationRequest("K-01", null, null, "a".repeat(51), null))).isNotEmpty();
    }

    @Test
    void clientChuyenVaNhanDayDuTruongBoSung() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpReferenceCatalogClient client = new HttpReferenceCatalogClient(builder.build());
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/shelf-locations/" + id)).andExpect(method(org.springframework.http.HttpMethod.PUT))
                .andExpect(jsonPath("$.floor").value("Tầng 2")).andExpect(jsonPath("$.capacity").value(60))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"code\":\"VH-01\",\"active\":true,\"floor\":\"Tầng 2\","
                        + "\"zone\":\"Khu Văn học\",\"capacity\":60}", MediaType.APPLICATION_JSON));
        ShelfLocationView v = client.updateShelfLocation(id, new SaveShelfLocationRequest("VH-01", null, "Tầng 2",
                "Khu Văn học", 60));
        assertThat(v.floor()).isEqualTo("Tầng 2");
        assertThat(v.zone()).isEqualTo("Khu Văn học");
        assertThat(v.capacity()).isEqualTo(60);

        server.verify();

        RestClient.Builder builder2 = RestClient.builder().baseUrl("http://catalog");
        MockRestServiceServer server2 = MockRestServiceServer.bindTo(builder2).build();
        client = new HttpReferenceCatalogClient(builder2.build());
        server2.expect(requestTo("http://catalog/internal/authors/" + id))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"name\":\"H\",\"active\":true,\"birthYear\":1942,"
                        + "\"deathYear\":2018,\"nationality\":\"Anh\",\"photoUrl\":null,\"website\":\"https://h.example\"}",
                        MediaType.APPLICATION_JSON));
        ReferenceView a = client.get("authors", id);
        assertThat(a.birthYear()).isEqualTo(1942);
        assertThat(a.nationality()).isEqualTo("Anh");
        assertThat(a.website()).isEqualTo("https://h.example");
        assertThat(a.country()).isNull();
    }
}
