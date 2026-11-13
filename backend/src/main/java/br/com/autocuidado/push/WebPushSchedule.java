package br.com.autocuidado.push;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

final class WebPushSchedule {
    private WebPushSchedule() {}

    static Optional<Instant> resolve(LocalDate date, LocalTime time, ZoneId zone) {
        var local = date.atTime(time);
        var rules = zone.getRules();
        var offsets = rules.getValidOffsets(local);
        if (!offsets.isEmpty()) return Optional.of(local.toInstant(offsets.getFirst()));

        var transition = rules.getTransition(local);
        if (!transition.getDateTimeAfter().toLocalDate().equals(date)) return Optional.empty();
        return Optional.of(transition.getInstant());
    }
}
