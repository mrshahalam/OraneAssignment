package com.workforce.backend.common.utility;

import java.time.DayOfWeek;
import java.time.LocalDate;

public final class WorkingDayCalculator {
    private WorkingDayCalculator() {}

    public static int count(LocalDate start, LocalDate end) {
        int days = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }
}
