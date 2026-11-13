package br.com.autocuidado.persistence;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "recurring_plans")
public class RecurringPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CareCategory category;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private int weeks;

    @Column(nullable = false)
    private LocalDate endDate;

    @ElementCollection
    @CollectionTable(name = "recurring_plan_weekdays", joinColumns = @JoinColumn(name = "plan_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "weekday", nullable = false, length = 9)
    private Set<DayOfWeek> weekdays = new HashSet<>();

    private LocalDate closedOn;

    @Column(columnDefinition = "text")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private CarePeriod period;

    private UUID creationRequestId;

    @Column(length = 64)
    private String creationRequestHash;

    protected RecurringPlan() {}

    RecurringPlan(Long accountId, CareCategory category, LocalDate startDate, int weeks,
                  LocalDate endDate, Set<DayOfWeek> weekdays, String title, CarePeriod period) {
        this.accountId = accountId;
        this.category = category;
        this.startDate = startDate;
        this.weeks = weeks;
        this.endDate = endDate;
        this.weekdays.addAll(weekdays);
        this.title = title;
        this.period = period;
    }

    Long id() {
        return id;
    }

    static RecurringPlan hair(long accountId, LocalDate startDate, int weeks, Set<DayOfWeek> weekdays,
                              UUID requestId, String requestHash) {
        return create(accountId, CareCategory.HAIR, startDate, weeks, weekdays, null, null, requestId, requestHash);
    }

    static RecurringPlan skin(long accountId, LocalDate startDate, int weeks, Set<DayOfWeek> weekdays,
                              String title, CarePeriod period, UUID requestId, String requestHash) {
        return create(accountId, CareCategory.SKIN, startDate, weeks, weekdays, title, period, requestId, requestHash);
    }

    private static RecurringPlan create(long accountId, CareCategory category, LocalDate startDate, int weeks,
                                        Set<DayOfWeek> weekdays, String title, CarePeriod period,
                                        UUID requestId, String requestHash) {
        var plan = new RecurringPlan(accountId, category, startDate, weeks,
                startDate.plusWeeks(weeks).minusDays(1), weekdays, title, period);
        plan.creationRequestId = requestId;
        plan.creationRequestHash = requestHash;
        return plan;
    }

    String creationRequestHash() {
        return creationRequestHash;
    }

    CareCategory category() {
        return category;
    }

    LocalDate startDate() {
        return startDate;
    }

    LocalDate closedOn() {
        return closedOn;
    }

    LocalDate close(LocalDate today) {
        if (closedOn == null) {
            closedOn = today;
        }
        return closedOn;
    }

    int weeks() {
        return weeks;
    }

    Set<DayOfWeek> weekdays() {
        return Set.copyOf(weekdays);
    }
}
