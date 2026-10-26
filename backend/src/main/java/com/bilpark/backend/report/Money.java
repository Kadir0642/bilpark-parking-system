package com.bilpark.backend.report;

/** A TL amount cell: numeric in Excel (#,##0.00), decimal comma in CSV. */
public record Money(double value) {
    public static Money of(Double v) {
        return new Money(v == null ? 0.0 : v);
    }
}
