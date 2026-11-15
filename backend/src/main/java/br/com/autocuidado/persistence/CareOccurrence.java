package br.com.autocuidado.persistence;

import java.time.LocalDate;

import br.com.autocuidado.hair.HairStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "care_occurrences")
public class CareOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    private Long planId;
    private Long cleaningSeriesId;

    @Column(nullable = false)
    private boolean cleaningCanceled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CareCategory category;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    private LocalDate plannedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private OccurrenceStatus status;

    private LocalDate completedOn;
    private LocalDate skippedOn;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private HairStage hairStage;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private CarePeriod period;

    @Column(columnDefinition = "text")
    private String note;

    @Column(columnDefinition = "text")
    private String polishColor;

    @Column(columnDefinition = "text")
    private String polishProduct;

    protected CareOccurrence() {}

    CareOccurrence(Long accountId, Long planId, CareCategory category, String title,
                   LocalDate plannedDate, HairStage hairStage) {
        this.accountId = accountId;
        this.planId = planId;
        this.category = category;
        this.title = title;
        this.plannedDate = plannedDate;
        this.hairStage = hairStage;
        this.status = OccurrenceStatus.PLANNED;
    }

    static CareOccurrence plannedSkin(long accountId, String title, LocalDate plannedDate, CarePeriod period) {
        var occurrence = new CareOccurrence(accountId, null, CareCategory.SKIN, title, plannedDate, null);
        occurrence.period = period;
        return occurrence;
    }

    static CareOccurrence plannedMakeup(long accountId, String title, LocalDate plannedDate, String note) {
        var occurrence = new CareOccurrence(accountId, null, CareCategory.MAKEUP, title, plannedDate, null);
        occurrence.note = note;
        return occurrence;
    }

    static CareOccurrence plannedNails(long accountId, String title, LocalDate plannedDate, String color, String product) {
        var occurrence = new CareOccurrence(accountId, null, CareCategory.NAILS, title, plannedDate, null);
        occurrence.polishColor = color;
        occurrence.polishProduct = product;
        return occurrence;
    }

    Long id() {
        return id;
    }

    LocalDate plannedDate() {
        return plannedDate;
    }

    Long planId() {
        return planId;
    }

    boolean isSeriesCleaning() {
        return cleaningSeriesId != null;
    }

    HairStage hairStage() {
        return hairStage;
    }

    OccurrenceStatus status() {
        return status;
    }

    void reschedule(LocalDate date) {
        plannedDate = date;
    }

    void complete(LocalDate date) {
        status = OccurrenceStatus.COMPLETED;
        completedOn = date;
    }

    void skip(LocalDate today) {
        status = OccurrenceStatus.SKIPPED;
        skippedOn = today;
    }

    void undo() {
        status = OccurrenceStatus.PLANNED;
        completedOn = null;
        skippedOn = null;
    }
}
