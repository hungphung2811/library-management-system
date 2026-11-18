package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lms.catalog.catalog.application.ListNewAuthorsUseCase;
import lms.catalog.catalog.application.NewAuthorsQuery;
import org.junit.jupiter.api.Test;

class ListNewAuthorsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private static class Fake implements NewAuthorsQuery {
        Instant since;
        int limit;
        List<String> rows = new ArrayList<>();

        @Override
        public List<String> findAuthorsWithTitlesSince(Instant since, int limit) {
            this.since = since;
            this.limit = limit;
            return rows;
        }
    }

    private final Fake query = new Fake();
    private final ListNewAuthorsUseCase useCase =
            new ListNewAuthorsUseCase(query, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void macDinh90NgayVaLimit200() {
        useCase.execute(null, null);
        assertEquals(NOW.minus(Duration.ofDays(90)), query.since);
        assertEquals(200, query.limit);
    }

    @Test
    void chanDaysVaLimit() {
        useCase.execute(0, 0);
        assertEquals(NOW.minus(Duration.ofDays(1)), query.since);
        assertEquals(1, query.limit);
        useCase.execute(9999, 9999);
        assertEquals(NOW.minus(Duration.ofDays(365)), query.since);
        assertEquals(200, query.limit);
        useCase.execute(-5, -5);
        assertEquals(NOW.minus(Duration.ofDays(1)), query.since);
        assertEquals(1, query.limit);
    }

    @Test
    void boTrungKhongPhanBietHoaThuongKhoangTrangVaTenRong() {
        query.rows = new ArrayList<>(Arrays.asList(" Robert Martin ", "robert martin", null, "  ", "Nguyễn Nhật Ánh",
                "ROBERT MARTIN"));
        assertEquals(List.of("Robert Martin", "Nguyễn Nhật Ánh"), useCase.execute(90, 200));
    }
}
