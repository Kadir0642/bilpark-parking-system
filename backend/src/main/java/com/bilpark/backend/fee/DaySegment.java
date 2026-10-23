package com.bilpark.backend.fee;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A contiguous piece of a stay inside one calendar day, either entirely paid or entirely free. */
public record DaySegment(LocalDate date, LocalDateTime start, LocalDateTime end, boolean paid, long minutes) {

    /** JSON-friendly form: date "yyyy-MM-dd", start/end ISO local date-time. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("start", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(start));
        m.put("end", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(end));
        m.put("paid", paid);
        m.put("minutes", minutes);
        return m;
    }

    public static List<Map<String, Object>> toMaps(List<DaySegment> segments) {
        return segments.stream().map(DaySegment::toMap).toList();
    }
}
