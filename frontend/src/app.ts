import { PageHeading } from './page-heading';
import { ACCOUNT_TIME_ZONE, accountToday } from './account-time-zone';
import { HairRoutine, type HairRoutineData } from './hair-routine';
import { isPlatformBrowser, Location } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, PLATFORM_ID, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { postWithCsrf } from './csrf';
import { formatHairDate, readHairPreviewDraft, STAGES, WEEKDAYS } from './hair-preview-draft';
import type { HairPlanRequest, HairPreviewDraft, HairPreviewState, HairSuggestionOptions, PreviewOccurrence, Stage, Weekday } from './hair-preview-draft';

type PreviewField = 'startDate' | 'weeks' | 'weekdays' | 'suggestion';
type FieldErrors = Partial<Record<PreviewField | 'timeZone' | 'body' | 'occurrences' | 'requestId', string>>;

interface PreviewResponse {
  occurrences: PreviewOccurrence[];
  suggestion?: HairRoutineData;
}

interface Occurrence extends PreviewOccurrence {
  label: string;
}

const compactDate = new Intl.DateTimeFormat('pt-BR', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
function shortSessionDate(date: string): string {
  const parts = compactDate.formatToParts(new Date(date + 'T00:00:00Z'));
  const text = (kind: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === kind)?.value.replace('.', '') ?? '';
  return `${text('weekday')}, ${text('day')} ${text('month')}`;
}

const STAGE_CUES: Record<Stage, { icon: string; hint: string }> = {
  WASH_ONLY: { icon: 'spa', hint: 'Cuidado básico de lavagem e condicionamento.' },
  HYDRATION: { icon: 'water_drop', hint: 'Hidratação adicionada por você nesta sessão.' },
  NUTRITION: { icon: 'opacity', hint: 'Nutrição adicionada por você nesta sessão.' },
  RECONSTRUCTION: { icon: 'auto_awesome', hint: 'Reconstrução adicionada por você nesta sessão.' },
};

function labelOccurrences(occurrences: readonly PreviewOccurrence[]): Occurrence[] {
  return occurrences.map(item => ({
    ...item,
    label: formatHairDate(item.date),
  }));
}

@Component({
  imports: [PageHeading, HairRoutine],
  selector: 'app-planner',
  templateUrl: './app.html',
})
export class App {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly draft = readHairPreviewDraft(this.router);
  private readonly heading = viewChild.required<ElementRef<HTMLHeadingElement>>('heading');
  private readonly dateInput = viewChild.required<ElementRef<HTMLInputElement>>('dateInput');
  private readonly weeksInput = viewChild.required<ElementRef<HTMLInputElement>>('weeksInput');
  private readonly weekdayFields = viewChild.required<ElementRef<HTMLFieldSetElement>>('weekdayFields');
  private previewRequest?: Subscription;
  private lastRequestId = this.draft?.requestId;
  private lastSavedDraft = this.draft && this.lastRequestId ? JSON.stringify({
    startDate: this.draft.startDate, weeks: this.draft.weeks,
    weekdays: this.draft.weekdays, occurrences: this.draft.occurrences, suggestion: this.draft.suggestion,
  }) : undefined;
  private previewSuggestion = this.draft?.suggestion;
  private datesOutdated = this.draft !== undefined;
  private editedStages = new Map(this.draft?.occurrences
    .filter(({ date }) => this.draft?.editedDates?.includes(date) ?? true)
    .map(({ date, stage }) => [date, stage]));

  protected readonly weekdays = WEEKDAYS;
  protected readonly stages = STAGES;
  protected readonly durations = [2, 4, 8, 12];
  protected readonly stageCues = STAGE_CUES;
  protected readonly compactDate = shortSessionDate;
  protected readonly timeZone = ACCOUNT_TIME_ZONE;
  protected readonly connectionMessage = signal('Verificando a conexão…');
  protected readonly startDate = signal(this.draft?.startDate ?? (this.browser ? accountToday() : ''));
  private readonly previewStart = signal(this.startDate());
  protected readonly weeks = signal<number | null>(this.draft?.weeks ?? 2);
  protected readonly selectedDays = signal<readonly Weekday[]>(this.draft?.weekdays ?? []);
  protected readonly suggested = signal(this.previewSuggestion !== undefined);
  protected readonly useProfile = signal(this.previewSuggestion?.useProfile ?? true);
  protected readonly routine = signal<HairRoutineData | null>(this.draft?.routine ?? null);
  protected readonly occurrences = signal(labelOccurrences(this.draft?.occurrences ?? []));
  protected readonly errors = signal<FieldErrors>({});
  protected readonly requestError = signal('');
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal('');
  protected readonly outdated = signal(this.draft !== undefined);
  protected readonly resuming = this.draft !== undefined;

  constructor() {
    if (!this.browser) return;
    this.http.get<{ status: string }>('/api/health').pipe(takeUntilDestroyed()).subscribe({
      next: ({ status }) => this.connectionMessage.set(
        status === 'UP' ? 'Conexão com a API confirmada.' : 'API indisponível.',
      ),
      error: () => this.connectionMessage.set('API indisponível.'),
    });
    if (this.resuming) {
      this.requestPreview(false, this.draft);
      afterNextRender(() => this.heading().nativeElement.focus());
    }
  }

  protected changeStartDate(value: string): void {
    this.startDate.set(value);
    this.invalidatePreview('startDate');
  }

  protected changeWeeks(value: number): void {
    this.weeks.set(Number.isNaN(value) ? null : value);
    this.invalidatePreview('weeks');
  }

  protected cycleWeek(date: string): number {
    return Math.floor((Date.parse(date) - Date.parse(this.previewStart())) / 604_800_000) + 1;
  }

  protected toggleDay(day: Weekday): void {
    this.selectedDays.update(days => days.includes(day)
      ? days.filter(selected => selected !== day)
      : [...days, day]);
    this.invalidatePreview('weekdays');
  }

  protected changeStage(date: string, stage: string): void {
    this.editedStages.set(date, stage as Stage);
    this.occurrences.update(items => items.map(item => item.date === date
      ? { ...item, stage: stage as Stage }
      : item));
    this.saveError.set('');
  }

  protected changeMode(suggested: boolean): void {
    this.suggested.set(suggested);
    this.invalidatePreview('suggestion');
  }

  protected changeUseProfile(useProfile: boolean): void {
    this.useProfile.set(useProfile);
    this.invalidatePreview('suggestion');
  }

  protected savePlan(): void {
    const draft = this.currentDraft();
    if (!draft) {
      return;
    }

    // Keep the same identifier after a failed response or reload when the choices are unchanged.
    const savedDraft = JSON.stringify({ ...draft, suggestion: this.previewSuggestion });
    const requestId = (savedDraft === this.lastSavedDraft && this.lastRequestId) || crypto.randomUUID();
    const request: HairPlanRequest = { ...draft, requestId, ...(this.previewSuggestion ? { suggestion: this.previewSuggestion } : {}) };
    const resumeDraft: HairPreviewState = {
      ...request,
      suggestion: this.previewSuggestion,
      routine: this.routine() ?? undefined,
      editedDates: [...this.editedStages.keys()],
    };
    this.lastRequestId = requestId;
    this.lastSavedDraft = savedDraft;
    this.location.replaceState(this.router.url, '', {
      ...this.location.getState() as object,
      hairPreviewDraft: resumeDraft,
    });

    this.saving.set(true);
    this.saveError.set('');
    postWithCsrf<{ planId: number }>(this.http, '/api/hair-plans', request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ planId }) => {
          void this.router.navigate(['/cronogramas', planId], { replaceUrl: true });
        },
        error: (error: HttpErrorResponse) => {
          this.saving.set(false);
          if (error.status === 401) {
            void this.router.navigate(['/agenda'], { state: { hairPreviewDraft: resumeDraft } });
            return;
          }
          const fields: FieldErrors | undefined = error.status === 400 || error.status === 409 ? error.error?.errors : undefined;
          this.errors.set(fields ?? {});
          if (fields?.startDate || fields?.weeks || fields?.weekdays || fields?.occurrences) {
            this.datesOutdated = true;
            this.outdated.set(true);
            this.requestError.set(fields.occurrences ??
              'Confira os campos indicados e gere a prévia novamente. Suas escolhas foram mantidas.');
            this.focusFieldError(fields);
          } else {
            this.saveError.set(fields?.body ?? fields?.requestId ??
              'Não foi possível confirmar o salvamento. Tente novamente.');
          }
        },
      });
  }

  private currentDraft(): HairPreviewDraft | undefined {
    const weeks = this.weeks();
    if (weeks === null || this.loading() || this.saving() || this.outdated() || !this.occurrences().length) {
      return undefined;
    }
    return {
      startDate: this.startDate(),
      weeks,
      weekdays: WEEKDAYS.map(day => day.value).filter(day => this.selectedDays().includes(day)),
      occurrences: this.occurrences().map(({ date, stage }) => ({ date, stage })),
    };
  }

  protected generatePreview(dialog: HTMLDialogElement): void {
    if (this.loading() || this.saving()) return;

    const suggestion = this.suggestionOptions();
    if (this.editedStages.size && suggestion && (this.suggestionChanged(suggestion) || !this.datesOutdated)) {
      dialog.showModal();
      return;
    }
    this.requestPreview(false);
  }

  private suggestionOptions(): HairSuggestionOptions | undefined {
    return this.suggested() ? { useProfile: this.useProfile() } : undefined;
  }

  private suggestionChanged(suggestion: HairSuggestionOptions | undefined): boolean {
    return suggestion?.useProfile !== this.previewSuggestion?.useProfile;
  }

  protected keepEdits(): void {
    this.suggested.set(this.previewSuggestion !== undefined);
    if (this.previewSuggestion) {
      this.useProfile.set(this.previewSuggestion.useProfile);
    }
    this.outdated.set(this.datesOutdated);
  }

  protected requestPreview(replaceEdits: boolean, resume?: HairPreviewState): void {
    if (this.loading() || this.saving()) {
      return;
    }

    this.errors.set({});
    this.requestError.set('');
    this.saveError.set('');
    this.loading.set(true);

    const startDate = this.startDate();
    const options = this.suggestionOptions();
    this.previewRequest = this.http.post<PreviewResponse>('/api/hair-plans/preview', {
      startDate,
      weeks: this.weeks(),
      weekdays: this.selectedDays(),
      timeZone: this.timeZone,
      ...(options ? { suggestion: options } : {}),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ occurrences, suggestion }) => {
        const dates = new Set(occurrences.map(item => item.date));
        if (replaceEdits) this.editedStages.clear();
        this.editedStages = new Map([...this.editedStages].filter(([date]) => dates.has(date)));
        const stages = resume ? new Map(resume.occurrences.map(({ date, stage }) => [date, stage])) : this.editedStages;
        this.occurrences.set(labelOccurrences(occurrences.map(({ date, stage }) => ({
          date, stage: stages.get(date) ?? stage,
        }))));
        this.routine.set(suggestion ?? resume?.routine ?? null);
        this.previewSuggestion = options;
        this.previewStart.set(startDate);
        this.datesOutdated = false;
        this.outdated.set(false);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        const fields: FieldErrors | undefined = error.status === 400 ? error.error?.errors : undefined;
        this.errors.set(fields ?? {});
        this.requestError.set(
          fields?.timeZone ?? fields?.body ?? fields?.suggestion ?? (fields
            ? (this.resuming && fields.startDate
              ? 'Ajuste a data de início e gere a prévia novamente. Suas escolhas foram mantidas.'
              : 'Confira os campos indicados e gere a prévia novamente.')
            : 'Não foi possível gerar a prévia. Tente novamente.'),
        );
        this.loading.set(false);
        this.focusFieldError(fields);
      },
    });
  }

  private focusFieldError(fields?: FieldErrors): void {
    if (!fields?.startDate && !fields?.weeks && !fields?.weekdays) {
      return;
    }
    afterNextRender(() => {
      const field = fields.startDate ? this.dateInput().nativeElement
        : fields.weeks ? this.weeksInput().nativeElement
          : this.weekdayFields().nativeElement.querySelector('button');
      field?.focus();
    }, { injector: this.injector });
  }

  private invalidatePreview(field: PreviewField): void {
    this.previewRequest?.unsubscribe();
    this.loading.set(false);
    if (this.occurrences().length) {
      if (field !== 'suggestion') this.datesOutdated = true;
      this.outdated.set(this.datesOutdated || this.suggestionChanged(this.suggestionOptions()));
    }
    this.errors.update(errors => ({ ...errors, [field]: undefined }));
    this.requestError.set('');
    this.saveError.set('');
  }
}
