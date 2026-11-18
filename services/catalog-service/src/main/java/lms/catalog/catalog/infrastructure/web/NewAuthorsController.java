package lms.catalog.catalog.infrastructure.web;

import java.util.List;
import lms.catalog.catalog.application.ListNewAuthorsUseCase;
import lms.security.permission.StorefrontAccessible;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tác giả có sách mới cho storefront (customer-api ghép với top bán chạy của sales). Khóa NewAuthorsController/list;
 * token dịch vụ storefront (svc) vào được, không kiểm khóa. Token integration bị 403 (không có @IntegrationScope).
 */
@RestController
@RequestMapping("/internal/books/new-authors")
public class NewAuthorsController {

    private final ListNewAuthorsUseCase listNewAuthors;

    public NewAuthorsController(ListNewAuthorsUseCase listNewAuthors) {
        this.listNewAuthors = listNewAuthors;
    }

    /** days mặc định 90 chặn 1..365; limit mặc định 200 chặn 1..200 (chặn ở use case, không trả 400). */
    public record NewAuthorsParams(Integer days, Integer limit) {
    }

    @GetMapping
    @StorefrontAccessible
    public List<String> list(NewAuthorsParams params) {
        return listNewAuthors.execute(params.days(), params.limit());
    }
}
