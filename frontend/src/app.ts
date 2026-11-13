import { isPlatformBrowser, Location } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, PLATFORM_ID, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { postWithCsrf } from './csrf';
import { formatHairDate, readHairPreviewDraft, STAGES, WEEKDAYS } from './hair-preview-draft';
import type { HairPlanRequest, HairPreviewDraft, PreviewOccurrence, Stage, Weekday } from './hair-preview-draft';

type PreviewField = 'startDate' | 'weeks' | 'weekdays';
type FieldErrors = Partial<Record<PreviewField | 'timeZone' | 'body' | 'occurrences' | 'requestId', string>>;

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
  WASH_ONLY: { icon: 'spa', hint: 'Categoria relacionada: shampoo e condicionador.' },
  HYDRATION: { icon: 'water_drop', hint: 'Categoria relacionada: máscara de hidratação.' },
  NUTRITION: { icon: 'opacity', hint: 'Categorias relacionadas: máscara de nutrição ou óleo capilar.' },
  RECONSTRUCTION: { icon: 'auto_awesome', hint: 'Categoria relacionada: máscara de reconstrução.' },
};

function labelOccurrences(occurrences: readonly PreviewOccurrence[]): Occurrence[] {
  return occurrences.map(item => ({
    ...item,
    label: formatHairDate(item.date),
  }));
}

function localToday(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

@Component({
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
  private lastSave = this.draft && 'requestId' in this.draft ? this.draft : undefined;

  protected readonly weekdays = WEEKDAYS;
  protected readonly stages = STAGES;
  protected readonly durations = [2, 4, 8, 12];
  protected readonly stageCues = STAGE_CUES;
  protected readonly compactDate = shortSessionDate;
  protected readonly timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  protected readonly connectionMessage = signal('Verificando a conexão…');
  protected readonly startDate = signal(this.draft?.startDate ?? (this.browser ? localToday() : ''));
  private readonly previewStart = signal(this.startDate());
  protected readonly weeks = signal<number | null>(this.draft?.weeks ?? 2);
  protected readonly selectedDays = signal<readonly Weekday[]>(this.draft?.weekdays ?? []);
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
      this.generatePreview();
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
    this.occurrences.update(items => items.map(item => item.date === date
      ? { ...item, stage: stage as Stage }
      : item));
    this.saveError.set('');
  }

  protected continueWithAccount(): void {
    const draft = this.currentDraft();
    if (draft) {
      this.openAccount(draft);
    }
  }

  protected savePlan(): void {
    const draft = this.currentDraft();
    if (!draft || !this.resuming) {
      return;
    }

    // Keep the same identifier after a failed response or reload when the choices are unchanged.
    const previous = this.lastSave;
    const requestId = previous && JSON.stringify(draft) === JSON.stringify({
      startDate: previous.startDate, weeks: previous.weeks,
      weekdays: previous.weekdays, occurrences: previous.occurrences,
    }) ? previous.requestId : crypto.randomUUID();
    const request: HairPlanRequest = { ...draft, requestId };
    this.lastSave = request;
    this.location.replaceState(this.router.url, '', {
      ...this.location.getState() as object,
      hairPreviewDraft: request,
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
            this.openAccount(request);
            return;
          }
          const fields: FieldErrors | undefined = error.status === 400 ? error.error?.errors : undefined;
          this.errors.set(fields ?? {});
          if (fields?.startDate || fields?.weeks || fields?.weekdays || fields?.occurrences) {
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

  private openAccount(draft: HairPreviewDraft): void {
    void this.router.navigate(['/agenda'], {
      state: { hairPreviewDraft: draft },
    });
  }

  protected generatePreview(): void {
    if (this.loading() || this.saving()) {
      return;
    }

    this.errors.set({});
    this.requestError.set('');
    this.saveError.set('');
    this.loading.set(true);

    const startDate = this.startDate();
    this.previewRequest = this.http.post<{ occurrences: PreviewOccurrence[] }>('/api/hair-plans/preview', {
      startDate,
      weeks: this.weeks(),
      weekdays: this.selectedDays(),
      timeZone: this.timeZone,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ occurrences }) => {
        const previousStages = new Map(this.occurrences().map(item => [item.date, item.stage]));
        this.occurrences.set(labelOccurrences(occurrences.map(({ date, stage }) => ({
          date, stage: previousStages.get(date) ?? stage,
        }))));
        this.previewStart.set(startDate);
        this.outdated.set(false);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        const fields: FieldErrors | undefined = error.status === 400 ? error.error?.errors : undefined;
        this.errors.set(fields ?? {});
        this.requestError.set(
          fields?.timeZone ?? fields?.body ?? (fields
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
    this.outdated.set(this.occurrences().length > 0);
    this.errors.update(errors => ({ ...errors, [field]: undefined }));
    this.requestError.set('');
    this.saveError.set('');
  }
}
