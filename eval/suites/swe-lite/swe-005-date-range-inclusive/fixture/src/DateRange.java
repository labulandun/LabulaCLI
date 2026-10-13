package benchmark;

import java.time.LocalDate;

public final class DateRange {

    private final LocalDate start;
    private final LocalDate end;

    public DateRange(LocalDate start, LocalDate end) {
        this.start = start;
        this.end = end;
    }

    public boolean contains(LocalDate date) {
        if (date == null || start == null || end == null) {
            return false;
        }
        return date.isAfter(start) && date.isBefore(end);
    }
}
