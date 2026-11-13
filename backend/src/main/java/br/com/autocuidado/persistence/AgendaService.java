package br.com.autocuidado.persistence;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.hair.HairStage;
import br.com.autocuidado.recurrence.RecurrenceDateGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static java.time.DayOfWeek.MONDAY;
import static java.time.temporal.TemporalAdjusters.previousOrSame;

@Service
public class AgendaService {

    private static final LocalDate MIN_DATE = LocalDate.of(1, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);
    private static final String AGENDA_VISIBILITY = """
              AND (o.status <> PLANNED OR NOT EXISTS (
                   SELECT 1 FROM RecurringPlan closedPlan
                   WHERE closedPlan.id = o.planId AND closedPlan.closedOn <= o.plannedDate))
            """;
    private static final String VISIBLE_OCCURRENCES = """
            SELECT o.id, o.title, o.category, o.plannedDate, o.hairStage,
                   COALESCE(o.period, p.period), o.status, o.completedOn, o.skippedOn,
                   CASE WHEN o.status = PLANNED AND p.closedOn IS NULL THEN true ELSE false END, o.planId,
                   o.note, o.polishColor, o.polishProduct
            FROM CareOccurrence o LEFT JOIN RecurringPlan p ON p.id = o.planId
            WHERE o.accountId = :accountId AND o.plannedDate IS NOT NULL
            """ + AGENDA_VISIBILITY;
    private static final String ORDER_BY_DATE = """
            ORDER BY o.plannedDate,
                     CASE COALESCE(o.period, p.period)
                         WHEN br.com.autocuidado.persistence.CarePeriod.MORNING THEN 0
                         WHEN br.com.autocuidado.persistence.CarePeriod.NIGHT THEN 1
                         ELSE 2 END,
                     o.id
            """;

    private final EntityManager entityManager;
    private final Clock clock;
    private final RecurrenceDateGenerator generator;
    private final AccountTimeZones timeZones;
    private final AgendaReminderService reminders;
    private final boolean careStreakEnabled;

    AgendaService(EntityManager entityManager, Clock clock, RecurrenceDateGenerator generator,
                  AccountTimeZones timeZones, AgendaReminderService reminders,
                  @Value("${app.agenda.care-streak-enabled:false}") boolean careStreakEnabled) {
        this.entityManager = entityManager;
        this.clock = clock;
        this.generator = generator;
        this.timeZones = timeZones;
        this.reminders = reminders;
        this.careStreakEnabled = careStreakEnabled;
    }

    @Transactional
    public Long scheduleSkinCare(AccountPrincipal account, String title, LocalDate plannedDate, CarePeriod period) {
        return scheduleStandalone(account, CareOccurrence.plannedSkin(account.accountId(), title, plannedDate, period));
    }

    @Transactional
    public Long scheduleMakeup(AccountPrincipal account, String title, LocalDate plannedDate, String note) {
        return scheduleStandalone(account, CareOccurrence.plannedMakeup(account.accountId(), title, plannedDate, note));
    }

    @Transactional
    public Long scheduleNails(AccountPrincipal account, String title, LocalDate plannedDate, String color, String product) {
        return scheduleStandalone(account, CareOccurrence.plannedNails(account.accountId(), title, plannedDate, color, product));
    }

    private Long scheduleStandalone(AccountPrincipal account, CareOccurrence occurrence) {
        if (occurrence.plannedDate().isBefore(today(account))) {
            return null;
        }
        entityManager.persist(occurrence);
        return occurrence.id();
    }

    @Transactional
    public Long recordMakeup(AccountPrincipal account, String title, LocalDate completedOn, String note) {
        return recordStandalone(account, CareOccurrence.plannedMakeup(account.accountId(), title, null, note), completedOn);
    }

    @Transactional
    public Long recordNails(AccountPrincipal account, String title, LocalDate completedOn, String color, String product) {
        return recordStandalone(account, CareOccurrence.plannedNails(account.accountId(), title, null, color, product), completedOn);
    }

    private Long recordStandalone(AccountPrincipal account, CareOccurrence occurrence, LocalDate completedOn) {
        if (!isCompletionDateValid(completedOn, today(account))) {
            return null;
        }
        occurrence.complete(completedOn);
        entityManager.persist(occurrence);
        return occurrence.id();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long scheduleSkinPlan(AccountPrincipal account, UUID requestId, LocalDate startDate, int weeks,
                                 Set<DayOfWeek> weekdays, String title, CarePeriod period) {
        long accountId = account.accountId();
        String requestHash = PlanCreation.hash("SKIN|" + startDate + "|" + weeks + "|"
                + weekdays.stream().sorted().toList() + "|" + period + "|" + title);
        var previous = PlanCreation.findRetry(entityManager, accountId, requestId, requestHash);
        if (previous != null) {
            return previous.id();
        }

        var dates = generator.generate(startDate, weeks, weekdays, timeZones.current(accountId));
        var plan = RecurringPlan.skin(accountId, startDate, weeks, weekdays, title, period, requestId, requestHash);
        entityManager.persist(plan);
        for (LocalDate date : dates) {
            entityManager.persist(new CareOccurrence(accountId, plan.id(), CareCategory.SKIN, title, date, null));
        }
        return plan.id();
    }

    @Transactional
    public boolean closeSkinPlan(AccountPrincipal account, long planId) {
        var plan = lockPlan(account, planId);
        if (plan == null || plan.category() != CareCategory.SKIN) {
            return false;
        }
        plan.close(today(account));
        return true;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Week loadWeek(AccountPrincipal account, LocalDate date) {
        LocalDate today = today(account);
        LocalDate start = (date == null ? today : date).with(previousOrSame(MONDAY));
        LocalDate end = start.plusDays(6);
        if (end.isAfter(MAX_DATE)) {
            end = MAX_DATE;
        }
        long accountId = account.accountId();

        var items = entityManager.createQuery(VISIBLE_OCCURRENCES + """
                AND o.plannedDate BETWEEN :start AND :end
                AND (o.status <> PLANNED OR o.plannedDate >= :today)
                """ + ORDER_BY_DATE, Item.class)
                .setParameter("accountId", accountId).setParameter("today", today)
                .setParameter("start", start).setParameter("end", end)
                .getResultList();
        var overdue = entityManager.createQuery(VISIBLE_OCCURRENCES + """
                AND o.status = PLANNED AND o.plannedDate < :today
                """ + ORDER_BY_DATE, Item.class)
                .setParameter("accountId", accountId).setParameter("today", today)
                .getResultList();
        var nextCare = entityManager.createQuery(VISIBLE_OCCURRENCES + """
                AND o.status = PLANNED AND o.plannedDate >= :today
                """ + ORDER_BY_DATE, Item.class)
                .setParameter("accountId", accountId).setParameter("today", today)
                .setMaxResults(1).getSingleResultOrNull();
        long completedCount = entityManager.createQuery("""
                SELECT COUNT(o) FROM CareOccurrence o
                WHERE o.accountId = :accountId AND o.status = COMPLETED AND o.completedOn BETWEEN :start AND :end
                """, Long.class)
                .setParameter("accountId", accountId)
                .setParameter("start", start).setParameter("end", end)
                .getSingleResult();

        // Derive the in-agenda channel from the same snapshot as the occurrence state.
        var preferences = reminders.preferences(accountId);
        if (preferences.enabled()) {
            items = items.stream().map(item -> item.withReminder(preferences)).toList();
            if (nextCare != null) nextCare = nextCare.withReminder(preferences);
        }
        return new Week(today, start.datesUntil(end.plusDays(1)).toList(),
                start.isAfter(MIN_DATE) ? start.minusWeeks(1) : null,
                end.isBefore(MAX_DATE) ? start.plusWeeks(1) : null,
                items, overdue, nextCare, completedCount, careStreakEnabled ? countCareStreak(accountId, today) : null);
    }

    private int countCareStreak(long accountId, LocalDate today) {
        var dates = entityManager.createQuery("""
                SELECT DISTINCT o.completedOn FROM CareOccurrence o
                WHERE o.accountId = :accountId AND o.status = COMPLETED AND o.completedOn <= :today
                ORDER BY o.completedOn DESC
                """, LocalDate.class)
                .setParameter("accountId", accountId).setParameter("today", today)
                .getResultList();
        LocalDate expected = !dates.isEmpty() && dates.getFirst().equals(today) ? today : today.minusDays(1);
        int streak = 0;
        for (LocalDate date : dates) {
            if (!date.equals(expected)) {
                break;
            }
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> loadHistory(AccountPrincipal account) {
        return entityManager.createQuery("""
                SELECT o.id, o.title, o.category, o.hairStage, COALESCE(o.period, p.period),
                       o.status, COALESCE(o.completedOn, o.skippedOn),
                       CASE WHEN o.plannedDate IS NOT NULL THEN true ELSE false END,
                       o.note, o.polishColor, o.polishProduct
                FROM CareOccurrence o LEFT JOIN RecurringPlan p ON p.id = o.planId
                WHERE o.accountId = :accountId AND o.status IN (COMPLETED, SKIPPED)
                ORDER BY COALESCE(o.completedOn, o.skippedOn) DESC, o.id DESC
                """, HistoryItem.class)
                .setParameter("accountId", account.accountId())
                .getResultList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActionResult act(AccountPrincipal account, long occurrenceId, Action action, LocalDate completedOn) {
        var occurrence = lockVisibleOccurrence(account, occurrenceId);
        if (occurrence == null) {
            return ActionResult.NOT_FOUND;
        }
        if (occurrence.plannedDate() == null) {
            return ActionResult.CONFLICT;
        }

        var status = occurrence.status();
        if (action == Action.UNDO) {
            if (status == OccurrenceStatus.PLANNED) {
                return ActionResult.CONFLICT;
            }
            occurrence.undo();
            return ActionResult.APPLIED;
        }

        if (status == OccurrenceStatus.PLANNED && occurrence.planId() != null) {
            var plan = lockPlan(account, occurrence.planId());
            if (plan == null || (plan.closedOn() != null && !occurrence.plannedDate().isBefore(plan.closedOn()))) {
                return ActionResult.NOT_FOUND;
            }
        }
        LocalDate today = today(account);
        if (action == Action.COMPLETE && !isCompletionDateValid(completedOn, today)) {
            return ActionResult.INVALID_COMPLETION_DATE;
        }
        var target = action == Action.COMPLETE ? OccurrenceStatus.COMPLETED : OccurrenceStatus.SKIPPED;
        if (status == target) {
            return ActionResult.APPLIED;
        }
        if (status != OccurrenceStatus.PLANNED) {
            return ActionResult.CONFLICT;
        }
        if (action == Action.COMPLETE) {
            occurrence.complete(completedOn);
        } else {
            occurrence.skip(today);
        }
        return ActionResult.APPLIED;
    }

    private static boolean isCompletionDateValid(LocalDate completedOn, LocalDate today) {
        return completedOn != null && !completedOn.isAfter(today);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RescheduleResult reschedule(AccountPrincipal account, long occurrenceId, LocalDate plannedDate) {
        var occurrence = lockVisibleOccurrence(account, occurrenceId);
        if (occurrence == null) {
            return RescheduleResult.NOT_FOUND;
        }
        if (occurrence.status() != OccurrenceStatus.PLANNED) {
            return RescheduleResult.CONFLICT;
        }
        if (plannedDate.isBefore(today(account))) {
            return RescheduleResult.PAST_DATE;
        }
        Long planId = occurrence.planId();
        if (planId != null) {
            // Serialize moves within a plan so the date check sees any preceding move after it commits.
            var plan = lockPlan(account, planId);
            if (plan == null || plan.closedOn() != null) {
                return RescheduleResult.CONFLICT;
            }
            var occupied = entityManager.createQuery("""
                    SELECT o.id FROM CareOccurrence o
                    WHERE o.planId = :planId AND o.plannedDate = :date AND o.id <> :id
                    """, Long.class)
                    .setParameter("planId", planId)
                    .setParameter("date", plannedDate)
                    .setParameter("id", occurrenceId)
                    .setMaxResults(1)
                    .getSingleResultOrNull();
            if (occupied != null) {
                return RescheduleResult.OCCUPIED_DATE;
            }
        }
        occurrence.reschedule(plannedDate);
        return RescheduleResult.APPLIED;
    }

    private CareOccurrence lockVisibleOccurrence(AccountPrincipal account, long occurrenceId) {
        return entityManager.createQuery("""
                SELECT o FROM CareOccurrence o WHERE o.id = :id AND o.accountId = :accountId
                """ + AGENDA_VISIBILITY, CareOccurrence.class)
                .setParameter("id", occurrenceId)
                .setParameter("accountId", account.accountId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResultOrNull();
    }

    private RecurringPlan lockPlan(AccountPrincipal account, long planId) {
        return entityManager.createQuery("""
                FROM RecurringPlan WHERE id = :planId AND accountId = :accountId
                """, RecurringPlan.class)
                .setParameter("planId", planId)
                .setParameter("accountId", account.accountId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResultOrNull();
    }

    private LocalDate today(AccountPrincipal account) {
        return LocalDate.now(clock.withZone(timeZones.current(account.accountId())));
    }

    public enum Action { COMPLETE, SKIP, UNDO }

    public enum ActionResult { APPLIED, NOT_FOUND, CONFLICT, INVALID_COMPLETION_DATE }

    public enum RescheduleResult { APPLIED, NOT_FOUND, CONFLICT, PAST_DATE, OCCUPIED_DATE }

    public record Item(Long id, String title, CareCategory category, LocalDate date, HairStage stage,
                       CarePeriod period, OccurrenceStatus status, LocalDate completedOn, LocalDate skippedOn,
                       boolean canReschedule, Long planId, String note, String polishColor, String polishProduct,
                       String reminderTime) {
        public Item(Long id, String title, CareCategory category, LocalDate date, HairStage stage,
                    CarePeriod period, OccurrenceStatus status, LocalDate completedOn, LocalDate skippedOn,
                    boolean canReschedule, Long planId, String note, String polishColor, String polishProduct) {
            this(id, title, category, date, stage, period, status, completedOn, skippedOn, canReschedule, planId,
                    note, polishColor, polishProduct, null);
        }

        Item withReminder(AgendaReminderService.Preferences preferences) {
            if (status != OccurrenceStatus.PLANNED || !preferences.categories().contains(category)) return this;
            return new Item(id, title, category, date, stage, period, status, completedOn, skippedOn,
                    canReschedule, planId, note, polishColor, polishProduct, preferences.time().toString());
        }
    }

    public record Week(LocalDate today, List<LocalDate> days, LocalDate previousWeek, LocalDate nextWeek,
                       List<Item> items, List<Item> overdue, Item nextCare, long completedCount,
                       Integer careStreakDays) {}

    public record HistoryItem(Long id, String title, CareCategory category, HairStage stage, CarePeriod period,
                              OccurrenceStatus status, LocalDate actionDate, boolean canUndo,
                              String note, String polishColor, String polishProduct) {}
}
