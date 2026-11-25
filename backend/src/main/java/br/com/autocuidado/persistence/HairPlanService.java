package br.com.autocuidado.persistence;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
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
import br.com.autocuidado.recurrence.RecurrenceDateGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.annotation.Transactional;

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
        long accountId = account.accountId();
        String requestHash = requestHash(startDate, weeks, weekdays, stages, useProfile);

        var previous = PlanCreation.findRetry(entityManager, accountId, requestId, requestHash);
        if (previous != null) {
            return previous.id();
        }

        var dates = generator.generate(startDate, weeks, weekdays, timeZones.current(accountId));
        if (!stages.keySet().equals(new HashSet<>(dates))) {
            throw new PreviewMismatchException();
        }

        var plan = RecurringPlan.hair(accountId, startDate, weeks, weekdays, requestId, requestHash);
        if (useProfile != null) {
            var profile = profileEnabled && useProfile ? profiles.find(accountId) : json.createObjectNode();
            plan.hairRoutine(json.writeValueAsString(HairCareRoutine.from(profile)));
        }
        entityManager.persist(plan);
        for (LocalDate date : dates) {
            entityManager.persist(new CareOccurrence(accountId, plan.id(), CareCategory.HAIR,
                    "Lavar cabelo", date, stages.get(date)));
        }
        return plan.id();
    }

    @Transactional(readOnly = true)
    public Optional<SavedPlan> find(long accountId, long planId) {
        RecurringPlan plan = findPlan(accountId, planId, LockModeType.NONE);
        if (plan == null) {
            return Optional.empty();
        }

        var occurrences = entityManager.createQuery("""
                FROM CareOccurrence WHERE planId = :planId AND accountId = :accountId ORDER BY plannedDate
                """, CareOccurrence.class)
                .setParameter("planId", planId)
                .setParameter("accountId", accountId)
                .getResultList().stream()
                .map(item -> new Occurrence(item.plannedDate(), item.hairStage()))
                .toList();
        return Optional.of(new SavedPlan(plan.id(), plan.startDate(), plan.weeks(),
                plan.weekdays().stream().sorted().toList(), occurrences, plan.closedOn(),
                plan.hairRoutine() == null ? null : json.readValue(plan.hairRoutine(), HairCareRoutine.class)));
    }

    @Transactional
    public Optional<ClosedPlan> close(AccountPrincipal account, long planId) {
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

    private static String requestHash(LocalDate startDate, int weeks, Set<DayOfWeek> weekdays,
                                      Map<LocalDate, HairStage> stages, Boolean useProfile) {
        return PlanCreation.hash(startDate + "|" + weeks + "|" + weekdays.stream().sorted().toList()
                + "|" + new TreeMap<>(stages) + (useProfile == null ? "" : "|daily-hair-care-v2|" + useProfile));
    }

    public record Occurrence(LocalDate date, HairStage stage) {}

    public record SavedPlan(long planId, LocalDate startDate, int weeks, List<DayOfWeek> weekdays,
                            List<Occurrence> occurrences, LocalDate closedOn, HairCareRoutine routine) {}

    public record ClosedPlan(LocalDate closedOn) {}

    public static final class PreviewMismatchException extends RuntimeException {}
}
