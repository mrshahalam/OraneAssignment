package com.workforce.backend.common.utility;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkingDayCalculatorTests {
    @Test
    void countsMondayToFridayOnly() {
        assertEquals(5, WorkingDayCalculator.count(
            LocalDate.of(2026, 11, 23),
            LocalDate.of(2026, 11, 29)));
    }
}
