package lms.catalog.catalog.application;

/** Gói sáu use case danh mục để controller của từng danh mục dùng chung (kind chọn bảng). */
public record ReferenceUseCases(CreateReferenceUseCase create, UpdateReferenceUseCase update,
                                SetReferenceStatusUseCase setStatus, GetReferenceUseCase get,
                                SearchReferencesUseCase search, ReferenceOptionsUseCase options) {
}
