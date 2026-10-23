package com.bilpark.backend.fee;

import java.util.List;

/** Result of a fee calculation: totals plus the time-ordered paid/free segments. */
public record FeeBreakdown(long totalMinutes, long paidMinutes, long freeMinutes, double fee, List<DaySegment> segments) {
}
