package lms.sales.stock.domain;

/** Loại biến động sổ cái (ADR-0010 mục 2.5). */
public enum MovementType {
    RECEIPT,
    SALE,
    RESERVE,
    RELEASE,
    FULFIL,
    RETURN,
    WRITE_OFF,
    ADJUSTMENT,
    STOCKTAKE,
    CANCEL_RESTOCK;

    public boolean requiresReason() {
        return this == ADJUSTMENT || this == STOCKTAKE || this == WRITE_OFF;
    }
}
