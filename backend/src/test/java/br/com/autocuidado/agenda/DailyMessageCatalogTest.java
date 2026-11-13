package br.com.autocuidado.agenda;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyMessageCatalogTest {

    @Test
    void swapsTheFirstTwoMessagesWhenTheUncorrectedCycleWouldRepeatThePreviousLastMessage() {
        var start = LocalDate.of(2026, 11, 10);
        // For seed 0, the first uncorrected cycle ends with message 1 and the second starts with 1, 2.
        assertEquals(1, DailyMessageCatalog.forDate(1, 0, start, start.plusDays(29)).number());
        assertEquals(2, DailyMessageCatalog.forDate(1, 0, start, start.plusDays(30)).number());
        assertEquals(1, DailyMessageCatalog.forDate(1, 0, start, start.plusDays(31)).number());
    }
}
