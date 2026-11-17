package lms.catalog.inventory.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.catalog.inventory.application.CopyLookupView;
import lms.catalog.inventory.application.LookupCopiesUseCase;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyLookupItem;
import lms.catalog.inventory.domain.CopyStatus;
import org.junit.jupiter.api.Test;

class LookupCopiesUseCaseTest {

    private final BookCopyRepository repo = mock(BookCopyRepository.class);
    private final LookupCopiesUseCase useCase = new LookupCopiesUseCase(repo);

    private static CopyLookupItem item(UUID id, String barcode) {
        return new CopyLookupItem(id, UUID.randomUUID(), "Clean Code", "9780132350884", barcode, CopyStatus.LOANED);
    }

    @Test
    void khongCoIdKhongGoiRepository() {
        assertTrue(useCase.execute(List.of()).isEmpty());
        assertTrue(useCase.execute(null).isEmpty());
        verify(repo, never()).findLookupByIds(any());
    }

    @Test
    void giuThuTuYeuCauBoIdTrungVaIdKhongCoThiVang() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        // repository trả ngược thứ tự và thiếu một id (khác tenant hoặc không tồn tại)
        when(repo.findLookupByIds(any())).thenReturn(List.of(item(b, "B"), item(a, "A")));

        List<CopyLookupView> result = useCase.execute(List.of(a, missing, b, a));

        assertEquals(2, result.size());
        assertEquals(a, result.get(0).id());
        assertEquals(b, result.get(1).id());
        assertEquals("LOANED", result.get(0).status());
        assertEquals("Clean Code", result.get(0).titleName());
        assertEquals("9780132350884", result.get(0).isbn());
    }

    @Test
    void quaGioiHanLoBiTuChoi() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) ids.add(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(ids));
        verify(repo, never()).findLookupByIds(any());
    }

    @Test
    void dungMotTramIdVanQua() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) ids.add(UUID.randomUUID());
        when(repo.findLookupByIds(any())).thenReturn(List.of());
        assertTrue(useCase.execute(ids).isEmpty());
        verify(repo).findLookupByIds(any());
    }
}
