package br.com.autocuidado.persistence;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.hair.HairStage;
import br.com.autocuidado.hair.HairCareRoutine;
import br.com.autocuidado.hair.HairScheduleSuggestions;
import br.com.autocuidado.hair.HairSuggestionRequest;
import br.com.autocuidado.recurrence.RecurrenceDateGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class HairPlanService {

    private final RecurrenceDateGenerator generator;
    private final Clock clock;
    private final AccountTimeZones timeZones;
    private final HairProfileStore profiles;
    private final JsonMapper json;
    private final boolean profileEnabled;

    @PersistenceContext
    private EntityManager entityManager;

    HairPlanService(RecurrenceDateGenerator generator, Clock clock, AccountTimeZones timeZones,
                    HairProfileStore profiles, JsonMapper json,
                    @Value("${app.hair.profile-enabled:false}") boolean profileEnabled) {
        this.generator = generator;
        this.clock = clock;
        this.timeZones = timeZones;
        this.profiles = profiles;
        this.json = json;
        this.profileEnabled = profileEnabled;
    }

    @Transactional
    public long create(AccountPrincipal account, UUID requestId, LocalDate startDate, int weeks,
                       Set<DayOfWeek> weekdays, Map<LocalDate, HairStage> stages) {
        return create(account, requestId, startDate, weeks, weekdays, stages, null);
    }

    @Transactional
    public long create(AccountPrincipal account, UUID requestId, LocalDate startDate, int weeks,
                       Set<DayOfWeek> weekdays, Map<LocalDate, HairStage> stages, Boolean useProfile) {
        return createPlan(account, requestId, startDate, weeks, weekdays, stages,
                useProfile == null ? null : new HairSuggestionRequest(useProfile, false, null, false));
    }

    @Transactional
    public long createSuggested(AccountPrincipal account, UUID requestId, LocalDate startDate, int weeks,
                                Set<DayOfWeek> weekdays, Map<LocalDate, HairStage> stages, HairSuggestionRequest options) {
        return createPlan(account, requestId, startDate, weeks, weekdays, stages, options);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SuggestedPreview preview(AccountPrincipal account, LocalDate startDate, int weeks,
                                    Set<DayOfWeek> weekdays, HairSuggestionRequest options) {
        var profile = profileEnabled && account != null && options.useProfile() ? profiles.find(account.accountId()) : json.createObjectNode();
        var selected = options.autoWeekdays() ? HairScheduleSuggestions.weekdays(profile, startDate) : weekdays;
        var dates = generator.generate(startDate, weeks, selected, AccountTimeZones.DEFAULT_ZONE);
        var routine = HairCareRoutine.from(profile);
        if (options.enhanced()) {
            var source = source(account, profile, dates.getLast());
            routine = routine.withSchedule(HairScheduleSuggestions.build(profile, dates, source.completed(), source.reserved(), source.revision()));
        }
        var care = routine;
        var occurrences = care.schedule() == null ? dates.stream().map(date -> new Occurrence(date, HairStage.WASH_ONLY)).toList()
                : care.schedule().sessions().stream().map(item -> new Occurrence(item.date(), item.stage())).toList();
        return new SuggestedPreview(occurrences, care, selected.stream().sorted().toList());
    }

    private long createPlan(AccountPrincipal account, UUID requestId, LocalDate startDate, int weeks,
                            Set<DayOfWeek> weekdays, Map<LocalDate, HairStage> stages, HairSuggestionRequest options) {
        long accountId = account.accountId();
        String requestHash = requestHash(startDate, weeks, weekdays, stages, options);

        var previous = PlanCreation.findRetry(entityManager, accountId, requestId, requestHash);
        if (previous != null) {
            return previous.id();
        }

        var dates = generator.generate(startDate, weeks, weekdays, timeZones.current(accountId));
        if (!stages.keySet().equals(new HashSet<>(dates))) {
            throw new PreviewMismatchException();
        }

        HairCareRoutine routine = null;
        if (options != null) {
            var profile = profileEnabled && options.useProfile() ? profiles.find(accountId) : json.createObjectNode();
            routine = HairCareRoutine.from(profile);
            if (options.enhanced()) {
                var source = source(account, profile, dates.getLast());
                if (!source.revision().equals(options.sourceRevision())) throw new SuggestionChangedException();
                if (options.autoWeekdays() && !HairScheduleSuggestions.weekdays(profile, startDate).equals(weekdays)) throw new PreviewMismatchException();
                routine = routine.withSchedule(HairScheduleSuggestions.build(profile, dates, source.completed(), source.reserved(), source.revision()));
            }
        }
        var plan = RecurringPlan.hair(accountId, startDate, weeks, weekdays, requestId, requestHash);
        entityManager.persist(plan);
        var ids = new TreeMap<LocalDate, Long>();
        for (LocalDate date : dates) {
            var occurrence = new CareOccurrence(accountId, plan.id(), CareCategory.HAIR, "Lavar cabelo", date, stages.get(date));
            entityManager.persist(occurrence);
            ids.put(date, occurrence.id());
        }
        if (routine != null) {
            if (routine.schedule() != null) {
                var schedule = routine.schedule();
                routine = routine.withSchedule(new HairScheduleSuggestions.Schedule(schedule.sourceRevision(), schedule.notes(),
                        schedule.sessions().stream().map(item -> new HairScheduleSuggestions.Session(ids.get(item.date()), item.date(), stages.get(item.date()),
                                stages.get(item.date()) == item.stage() ? item.reason()
                                        : "Cuidado escolhido por você na prévia. Confira a orientação e o intervalo desse cuidado; esta etapa não foi inferida pelo perfil.")).toList(), schedule.intervals()));
            }
            plan.hairRoutine(json.writeValueAsString(routine));
        }
        return plan.id();
    }

    @Transactional(readOnly = true)
    public Optional<SavedPlan> find(long accountId, long planId) {
        RecurringPlan plan = findPlan(accountId, planId, LockModeType.NONE);
        if (plan == null) {
            return Optional.empty();
        }

        var routine = plan.hairRoutine() == null ? null : json.readValue(plan.hairRoutine(), HairCareRoutine.class);
        var occurrences = entityManager.createQuery("""
                FROM CareOccurrence WHERE planId = :planId AND accountId = :accountId ORDER BY plannedDate
                """, CareOccurrence.class)
                .setParameter("planId", planId)
                .setParameter("accountId", accountId)
                .getResultList().stream()
                .map(item -> new Occurrence(item.plannedDate(), item.hairStage(), routine != null && routine.schedule() != null ? item.id() : null))
                .toList();
        return Optional.of(new SavedPlan(plan.id(), plan.startDate(), plan.weeks(),
                plan.weekdays().stream().sorted().toList(), occurrences, plan.closedOn(),
                routine));
    }

    @Transactional
    public Optional<ClosedPlan> close(AccountPrincipal account, long planId) {
        entityManager.find(Account.class, account.accountId(), LockModeType.PESSIMISTIC_WRITE);
        // Share the plan lock with agenda mutations that depend on it being open.
        RecurringPlan plan = findPlan(account.accountId(), planId, LockModeType.PESSIMISTIC_WRITE);
        if (plan == null) {
            return Optional.empty();
        }
        return Optional.of(new ClosedPlan(plan.close(LocalDate.now(clock.withZone(timeZones.current(account.accountId()))))));
    }

    private RecurringPlan findPlan(long accountId, long planId, LockModeType lock) {
        return entityManager.createQuery("""
                FROM RecurringPlan WHERE id = :planId AND accountId = :accountId AND category = :category
                """, RecurringPlan.class)
                .setParameter("planId", planId)
                .setParameter("accountId", accountId)
                .setParameter("category", CareCategory.HAIR)
                .setLockMode(lock)
                .getSingleResultOrNull();
    }

    private Source source(AccountPrincipal account, tools.jackson.databind.JsonNode profile, LocalDate end) {
        var completed = new EnumMap<HairStage, LocalDate>(HairStage.class);
        var reserved = new ArrayList<HairScheduleSuggestions.Reservation>();
        if (account != null) {
            for (var row : entityManager.createQuery("""
                    SELECT o.hairStage, max(o.completedOn) FROM CareOccurrence o
                    WHERE o.accountId = :accountId AND o.category = HAIR AND o.status = COMPLETED
                        AND o.hairStage <> WASH_ONLY GROUP BY o.hairStage
                    """, Object[].class).setParameter("accountId", account.accountId()).getResultList()) {
                completed.put((HairStage) row[0], (LocalDate) row[1]);
            }
            var today = LocalDate.now(clock.withZone(AccountTimeZones.DEFAULT_ZONE));
            var earliestReservation = end.minusWeeks(12).minusYears(1).minusDays(7);
            if (earliestReservation.isBefore(today)) earliestReservation = today;
            for (var row : entityManager.createQuery("""
                    SELECT DISTINCT o.hairStage, o.plannedDate FROM CareOccurrence o
                    WHERE o.accountId = :accountId AND o.category = HAIR AND o.status = PLANNED
                        AND o.hairStage <> WASH_ONLY AND o.plannedDate >= :today AND o.plannedDate <= :horizon
                        AND NOT EXISTS (SELECT 1 FROM RecurringPlan p WHERE p.id = o.planId AND p.closedOn <= o.plannedDate)
                    ORDER BY o.plannedDate, o.hairStage
                    """, Object[].class).setParameter("accountId", account.accountId()).setParameter("today", earliestReservation)
                    .setParameter("horizon", end.plusYears(1).plusDays(7)).getResultList()) {
                reserved.add(new HairScheduleSuggestions.Reservation((HairStage) row[0], (LocalDate) row[1]));
            }
        }
        completed.forEach((stage, date) -> reserved.add(new HairScheduleSuggestions.Reservation(stage, date)));
        return new Source(completed, reserved, PlanCreation.hash(profile.toString() + "|" + completed + "|" + reserved));
    }

    private static String requestHash(LocalDate startDate, int weeks, Set<DayOfWeek> weekdays,
                                      Map<LocalDate, HairStage> stages, HairSuggestionRequest options) {
        String suggestion = options == null ? "" : options.enhanced()
                ? "|hair-schedule-v3|" + options.useProfile() + "|" + options.autoWeekdays() + "|" + options.sourceRevision()
                : "|daily-hair-care-v2|" + options.useProfile();
        return PlanCreation.hash(startDate + "|" + weeks + "|" + weekdays.stream().sorted().toList()
                + "|" + new TreeMap<>(stages) + suggestion);
    }

    private record Source(Map<HairStage, LocalDate> completed, List<HairScheduleSuggestions.Reservation> reserved, String revision) {}
    public record SuggestedPreview(List<Occurrence> occurrences, HairCareRoutine suggestion, List<DayOfWeek> weekdays) {}
    public record Occurrence(LocalDate date, HairStage stage,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long id) {
        public Occurrence(LocalDate date, HairStage stage) { this(date, stage, null); }
    }

    public record SavedPlan(long planId, LocalDate startDate, int weeks, List<DayOfWeek> weekdays,
                            List<Occurrence> occurrences, LocalDate closedOn, HairCareRoutine routine) {}

    public record ClosedPlan(LocalDate closedOn) {}

    public static final class PreviewMismatchException extends RuntimeException {}
    public static final class SuggestionChangedException extends RuntimeException {}
}
