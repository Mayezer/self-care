package br.com.autocuidado.hair;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Calendar rules, not a diagnosis of the fibre or a prescription of a cosmetic formula. */
public final class HairScheduleSuggestions {
    private HairScheduleSuggestions() {}

    public static Set<DayOfWeek> weekdays(JsonNode profile, LocalDate start) {
        int count = profile.path("careSchedule").path("washesPerWeek").asInt(0);
        if (count < 1 || count > 7) {
            throw new MissingDetailsException("Em Meu cabelo, informe quantas lavagens por semana são confortáveis para você, ou escolha os dias manualmente.");
        }
        var result = EnumSet.noneOf(DayOfWeek.class);
        for (int index = 0; index < count; index++) result.add(start.plusDays(index * 7 / count).getDayOfWeek());
        return result;
    }

    public static Schedule build(JsonNode profile, List<LocalDate> dates, Map<HairStage, LocalDate> completed,
                                 List<Reservation> reserved, String revision) {
        var preferences = profile.path("careSchedule");
        var notes = new ArrayList<String>();
        var rules = new ArrayList<Rule>();
        String symptoms = preferences.path("scalpSymptoms").asString("UNKNOWN");
        if (!symptoms.equals("NO")) {
            notes.add(symptoms.equals("YES")
                    ? "Cuidados extras não foram programados porque você relatou sinais de alerta. Procure avaliação dermatológica para irritação, feridas ou queda súbita; a agenda não identifica a causa."
                    : "Para programar cuidados extras, informe em Meu cabelo se há irritação, feridas ou queda súbita. Sem resposta não significa ausência desses sinais.");
        } else {
            for (HairStage stage : List.of(HairStage.HYDRATION, HairStage.NUTRITION, HairStage.RECONSTRUCTION)) {
                var input = preferences.path("treatments").path(stage.name());
                String status = input.path("status").asString("UNKNOWN");
                if (status.equals("AVOID")) {
                    notes.add(label(stage) + " excluída: você informou reação ou piora com esse cuidado.");
                    continue;
                }
                if (!Set.of("KNOWN", "FIRST_USE").contains(status)) {
                    notes.add(label(stage) + " não programada: falta informar uso conhecido/tolerado ou primeiro uso com orientação, e seu histórico.");
                    continue;
                }
                boolean supplied = input.path("every").isIntegralNumber();
                if (!supplied && (stage != HairStage.HYDRATION || !contains(profile.path("observations"), "DRYNESS"))) {
                    notes.add(label(stage) + " não programada: informe o intervalo de uso já orientado. O perfil não determina esse intervalo.");
                    continue;
                }
                int every = supplied ? input.path("every").asInt() : 1;
                String unit = supplied ? input.path("unit").asString() : "WEEKS";
                Period period = switch (unit) {
                    case "DAYS" -> Period.ofDays(every);
                    case "WEEKS" -> Period.ofWeeks(every);
                    case "MONTHS" -> Period.ofMonths(every);
                    default -> throw new IllegalArgumentException("Unsupported interval unit");
                };
                LocalDate last = status.equals("KNOWN") ? LocalDate.parse(input.path("lastUse").asString()) : null;
                var actual = completed.get(stage);
                if (actual != null && (last == null || actual.isAfter(last))) last = actual;
                rules.add(new Rule(stage, period, last == null ? dates.getFirst() : last.plus(period), supplied,
                        every + " " + unitLabel(unit, every), new Interval(stage, every, unit, last, supplied)));
            }
        }
        var sessions = new ArrayList<Session>();
        var allocations = new ArrayList<>(reserved);
        for (LocalDate date : dates) {
            var rule = rules.stream().filter(item -> !date.isBefore(item.due))
                    .filter(item -> fits(item, date, allocations))
                    .min(Comparator.comparing((Rule item) -> item.due).thenComparing(item -> item.stage)).orElse(null);
            if (rule == null) {
                sessions.add(new Session(null, date, HairStage.WASH_ONLY,
                        "Lavagem e condicionamento. Nenhum cuidado extra com os dados e intervalos atendidos nesta data."));
            } else {
                String reason = rule.informed
                        ? label(rule.stage) + " programada no intervalo de " + rule.interval + " informado por você; encaixada em um dia de lavagem."
                        : "Ressecamento relatado: opção cosmética de hidratação semanal, com seu histórico considerado. Ajuste se a orientação do seu cuidado for diferente.";
                sessions.add(new Session(null, date, rule.stage, reason));
                allocations.add(new Reservation(rule.stage, date));
                rule.due = date.plus(rule.period);
            }
        }
        for (Rule rule : rules) {
            if (!rule.due.isAfter(dates.getLast())) {
                notes.add(label(rule.stage) + " tem uma aplicação a encaixar após " + rule.due
                        + ": os dias disponíveis ou outro cuidado já programado impediram o encaixe neste ciclo. Não reduza o intervalo para compensar.");
            } else {
                notes.add(label(rule.stage) + ": próxima data de referência " + rule.due + ", após o fim deste ciclo.");
            }
        }
        notes.add("As datas são uma proposta para o histórico atual. Se aplicar um cuidado fora da data, reagendar ou editar uma etapa, confira os próximos intervalos; não compense aplicações perdidas.");
        notes.add("Hidratação semanal é uma orientação educativa geral para ressecamento. Nutrição e reconstrução seguem os intervalos que você informou, sem diagnóstico de falta de óleo ou queratina.");
        return new Schedule(revision, List.copyOf(notes), List.copyOf(sessions), rules.stream().map(rule -> rule.parameters).toList());
    }

    private static boolean fits(Rule rule, LocalDate date, List<Reservation> reserved) {
        if (reserved.stream().anyMatch(item -> item.date.equals(date))) return false;
        return reserved.stream().filter(item -> item.stage == rule.stage).noneMatch(item ->
                item.date.equals(date) || (item.date.isBefore(date) ? item.date.plus(rule.period).isAfter(date)
                        : date.plus(rule.period).isAfter(item.date)));
    }

    private static boolean contains(JsonNode values, String value) {
        for (var item : values) if (value.equals(item.asString(""))) return true;
        return false;
    }

    public static String label(HairStage stage) {
        return switch (stage) { case HYDRATION -> "Hidratação"; case NUTRITION -> "Nutrição";
            case RECONSTRUCTION -> "Reconstrução"; case WASH_ONLY -> "Lavagem e condicionamento"; };
    }

    private static String unitLabel(String unit, int every) {
        return switch (unit) { case "DAYS" -> every == 1 ? "dia" : "dias";
            case "WEEKS" -> every == 1 ? "semana" : "semanas"; default -> every == 1 ? "mês" : "meses"; };
    }

    private static final class Rule {
        final HairStage stage;
        final Period period;
        LocalDate due;
        final boolean informed;
        final String interval;
        final Interval parameters;
        Rule(HairStage stage, Period period, LocalDate due, boolean informed, String interval, Interval parameters) {
            this.stage = stage; this.period = period; this.due = due; this.informed = informed; this.interval = interval;
            this.parameters = parameters;
        }
    }

    public record Reservation(HairStage stage, LocalDate date) {}
    public record Session(@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                          Long occurrenceId, LocalDate date, HairStage stage, String reason) {}
    public record Interval(HairStage stage, int every, String unit, LocalDate lastApplication, boolean informed) {}
    public record Schedule(String sourceRevision, List<String> notes, List<Session> sessions, List<Interval> intervals) {}
    public static final class MissingDetailsException extends RuntimeException {
        public MissingDetailsException(String message) { super(message); }
    }
}
