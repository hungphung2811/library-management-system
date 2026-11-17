package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.catalog.catalog.application.GetTitlePreviewUseCase;
import lms.catalog.catalog.application.LookupTitlesByKeywordUseCase;
import lms.catalog.catalog.application.TitleLookupView;
import lms.catalog.catalog.application.TitlePreviewResponse;
import lms.catalog.catalog.application.UpdateTitlePreviewUseCase;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitlePreviewTooLongException;
import lms.catalog.catalog.domain.TitleRepository;
import org.junit.jupiter.api.Test;

/** Use case preview/mô tả và tra theo từ khóa của catalog (repository giả bằng Mockito). */
class TitlePreviewUseCasesTest {

    private final TitleRepository repository = mock(TitleRepository.class);
    private final Title title = Title.create("Clean Code", "Martin", "111", BigDecimal.TEN);

    @Test
    void getPreviewTraMoTaVaDoanDocThuKhongIsbn() {
        Title withPreview = title.withPreview("mo ta", "doc thu");
        when(repository.findById(title.id())).thenReturn(Optional.of(withPreview));
        TitlePreviewResponse r = new GetTitlePreviewUseCase(repository).execute(title.id());
        assertEquals(new TitlePreviewResponse(title.id(), "Clean Code", "mo ta", "doc thu"), r);
    }

    @Test
    void getPreviewChuaNhapTraNull() {
        when(repository.findById(title.id())).thenReturn(Optional.of(title));
        TitlePreviewResponse r = new GetTitlePreviewUseCase(repository).execute(title.id());
        assertNull(r.description());
        assertNull(r.previewText());
    }

    @Test
    void getPreviewKhongThay404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(TitleNotFoundException.class, () -> new GetTitlePreviewUseCase(repository).execute(id));
    }

    @Test
    void updatePreviewChuanHoaVaLuu() {
        when(repository.findById(title.id())).thenReturn(Optional.of(title));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TitlePreviewResponse r = new UpdateTitlePreviewUseCase(repository)
                .execute(title.id(), "  mo ta ", "a\r\nb\u0000");
        assertEquals("mo ta", r.description());
        assertEquals("a\nb", r.previewText());
        verify(repository).save(any());
    }

    @Test
    void updatePreviewQuaDaiKhongLuu() {
        when(repository.findById(title.id())).thenReturn(Optional.of(title));
        assertThrows(TitlePreviewTooLongException.class,
                () -> new UpdateTitlePreviewUseCase(repository).execute(title.id(), null, "a".repeat(2001)));
        verify(repository, never()).save(any());
    }

    @Test
    void updatePreviewRongXoaVaDauSachTenantKhacKhongThay404() {
        when(repository.findById(title.id())).thenReturn(Optional.of(title.withPreview("x", "y")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TitlePreviewResponse cleared = new UpdateTitlePreviewUseCase(repository).execute(title.id(), "", " ");
        assertNull(cleared.description());
        assertNull(cleared.previewText());

        UUID other = UUID.randomUUID();
        when(repository.findById(other)).thenReturn(Optional.empty());
        assertThrows(TitleNotFoundException.class,
                () -> new UpdateTitlePreviewUseCase(repository).execute(other, "a", "b"));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(t -> t.id().equals(other)));
    }

    @Test
    void lookupTheoTuKhoaToiDa200VaCoHasPreviewKhongCoPreviewText() {
        Title t = title.withPreview("mo ta", "doc thu");
        when(repository.searchLimited("clean", 200)).thenReturn(List.of(t));
        List<TitleLookupView> views = new LookupTitlesByKeywordUseCase(repository).execute("clean");
        assertEquals(1, views.size());
        assertEquals("mo ta", views.get(0).description());
        assertTrue(views.get(0).hasPreview());
        assertEquals(200, LookupTitlesByKeywordUseCase.MAX_RESULTS);
    }

    @Test
    void lookupViewChuaNhapThiHasPreviewFalse() {
        TitleLookupView v = TitleLookupView.from(title);
        assertNull(v.description());
        assertEquals(false, v.hasPreview());
    }
}
