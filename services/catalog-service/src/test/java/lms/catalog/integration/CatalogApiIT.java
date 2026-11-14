package lms.catalog.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cần Docker. Chạy bằng: mvn test -Dtest=CatalogApiIT -Dsurefire.failIfNoSpecifiedTests=false. Profile dev tắt security. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void taoDauSachNhapBanSaoRoiTimKiemThayBanSaoSanCo() throws Exception {
        String body = """
                {"name":"Clean Code","author":"Robert C. Martin","isbn":"9780132350884","coverPrice":250000}""";
        String created = mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableCopies").value(0))
                .andReturn().getResponse().getContentAsString();
        String titleId = json.readTree(created).get("id").asText();

        mvc.perform(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        mvc.perform(get("/internal/books").param("keyword", "clean").param("available", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + titleId + "\",\"barcode\":\"CC-001\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + titleId + "\",\"barcode\":\"CC-001\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/internal/books").param("keyword", "MARTIN").param("available", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].availableCopies").value(1));

        mvc.perform(get("/internal/books/" + titleId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCopies").value(1));
        mvc.perform(get("/internal/book-copies").param("titleId", titleId))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"00000000-0000-0000-0000-000000000009\",\"barcode\":\"ZZ\"}"))
                .andExpect(status().isNotFound());
    }
}
