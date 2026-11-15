import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender, Component, computed, DestroyRef, ElementRef, inject, Injector, input, output, signal, viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { postWithCsrf } from './csrf';
import { WEEKDAYS, type Weekday } from './hair-preview-draft';
import {
  SKIN_PERIODS, suggestSkinRoutine, type SkinHabit, type SkinPeriod, type SkinTime, type SkinTiming,
} from './skin-routine-model';

interface PeriodDraft {
  enabled: boolean;
  title: string;
  weekdays: Weekday[];
  steps: { id: number; text: string }[];
}

type Mode = 'choice' | 'questionnaire' | 'draft';

@Component({
  selector: 'app-skin-routine',
  templateUrl: './skin-routine.html',
})
export class SkinRoutine {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('heading');
  private readonly errorSummary = viewChild<ElementRef<HTMLElement>>('errorSummary');
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private nextStepId = 0;
  private lastRequest?: { body: string; id: string };

  readonly today = input.required<string>();
  readonly savingChange = output<boolean>();

  protected readonly mode = signal<Mode>('choice');
  protected readonly timing = signal<SkinTiming>('');
  protected readonly time = signal<SkinTime>('');
  protected readonly habit = signal<SkinHabit>('');
  protected readonly perception = signal('');
  protected readonly reason = signal('');
  protected readonly start = signal<string | null>(null);
  protected readonly weeks = signal('1');
  protected readonly periods = SKIN_PERIODS;
  protected readonly weekdays = WEEKDAYS;
  protected readonly drafts = signal(this.blankDraft());
  protected readonly errors = signal<Record<string, string>>({});
  protected readonly saving = signal(false);
  protected readonly errorMessages = computed(() => [...new Set(Object.values(this.errors()))]);

  protected chooseTiming(value: string): void {
    this.timing.set(value as SkinTiming);
  }

  protected chooseTime(value: string): void {
    this.time.set(value as SkinTime);
  }

  protected chooseHabit(value: string): void {
    this.habit.set(value as SkinHabit);
  }


  protected choose(mode: 'questionnaire' | 'draft' | 'choice'): void {
    this.errors.set({});
    if (mode === 'draft') {
      this.reason.set('');
      this.drafts.set(this.blankDraft(true));
    } else if (mode === 'choice') {
      this.timing.set('');
      this.time.set('');
      this.habit.set('');
      this.perception.set('');
      this.start.set(null);
      this.weeks.set('1');
    }
    this.mode.set(mode);
    this.focusHeading();
  }

  protected suggest(): void {
    const suggestion = suggestSkinRoutine(this.timing(), this.time(), this.habit());
    if (!suggestion) {
      this.choose('draft');
      this.reason.set('Nenhum modelo revisado está disponível. Você pode montar sua rotina.');
      return;
    }
    const draft = this.blankDraft();
    for (const period of suggestion.periods) {
      draft[period.period] = {
        enabled: true, title: period.title, weekdays: WEEKDAYS.map(day => day.value),
        steps: period.steps.map(step => this.step(step)),
      };
    }
    this.reason.set(suggestion.reason + (this.perception() && this.perception() !== 'NOTHING'
      ? ' A proposta mantém passos básicos; você pode ajustar o que fizer sentido para a sua pele.' : ''));
    this.drafts.set(draft);
    this.mode.set('draft');
    this.focusHeading();
  }

  protected update(period: SkinPeriod, changes: Partial<PeriodDraft>): void {
    this.drafts.update(drafts => ({ ...drafts, [period]: { ...drafts[period], ...changes } }));
  }

  protected toggleDay(period: SkinPeriod, day: Weekday): void {
    const days = this.drafts()[period].weekdays;
    this.update(period, { weekdays: days.includes(day) ? days.filter(value => value !== day) : [...days, day] });
  }

  protected editStep(period: SkinPeriod, id: number, text: string): void {
    this.update(period, { steps: this.drafts()[period].steps.map(step => step.id === id ? { ...step, text } : step) });
  }

  protected addStep(period: SkinPeriod): void {
    const step = this.step('');
    this.update(period, { steps: [...this.drafts()[period].steps, step] });
    this.focusStep(period, step.id);
  }

  protected removeStep(period: SkinPeriod, index: number): void {
    const steps = this.drafts()[period].steps.filter((_, i) => i !== index);
    this.update(period, { steps });
    this.focusStep(period, steps[Math.min(index, steps.length - 1)].id);
  }

  protected save(): void {
    if (this.saving()) return;
    const startDate = this.start() ?? this.today();
    const weeks = Number(this.weeks());
    const drafts = this.drafts();
    const periods = SKIN_PERIODS.filter(period => drafts[period.value].enabled).map(({ value: period }) => ({
      period, title: drafts[period].title.trim(),
      weekdays: WEEKDAYS.map(day => day.value).filter(day => drafts[period].weekdays.includes(day)),
      steps: drafts[period].steps.map(step => step.text.trim()),
    }));
    const errors: Record<string, string> = {};
    if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(startDate) || startDate < this.today()) {
      errors['startDate'] = 'Escolha a data de hoje ou uma data futura.';
    }
    if (!Number.isInteger(weeks) || weeks < 1 || weeks > 12) {
      errors['weeks'] = 'Escolha uma duração de 1 a 12 semanas.';
    }
    if (!periods.length) errors['periods'] = 'Escolha pelo menos um período.';
    for (const period of periods) {
      const label = SKIN_PERIODS.find(option => option.value === period.period)!.label;
      if (!period.title || period.title.length > 100) {
        errors[period.period + '.title'] = label + ': preencha o título com até 100 caracteres.';
      }
      if (!period.weekdays.length) errors[period.period + '.weekdays'] = label + ': selecione pelo menos um dia.';
      if (period.steps.some(step => !step || step.length > 120)) {
        errors[period.period + '.steps'] = label + ': preencha cada passo com até 120 caracteres.';
      }
    }
    if (Object.keys(errors).length) {
      this.showErrors(errors);
      return;
    }

    const body = { startDate, weeks, periods };
    const canonical = JSON.stringify(body);
    if (this.lastRequest?.body !== canonical) {
      this.lastRequest = { body: canonical, id: crypto.randomUUID() };
    }
    this.errors.set({});
    this.saving.set(true);
    this.savingChange.emit(true);
    postWithCsrf(this.http, '/api/skin-routines', { ...body, requestId: this.lastRequest.id })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => void this.router.navigate(['/agenda'], {
          queryParams: { date: startDate }, info: 'skin-plan-scheduled', replaceUrl: true,
        }),
        error: (response: HttpErrorResponse) => {
          this.saving.set(false);
          this.savingChange.emit(false);
          const fields: Record<string, string> = {};
          for (const [key, value] of Object.entries(response.error?.errors ?? {})) {
            if (typeof value !== 'string') continue;
            const label = SKIN_PERIODS.find(period => key.startsWith(period.value + '.'))?.label;
            fields[key] = label ? label + ': ' + value : value;
          }
          this.showErrors(Object.keys(fields).length ? fields : { request: response.status === 401
            ? 'Sua sessão terminou. Entre novamente para salvar a rotina.'
            : 'Não foi possível confirmar o salvamento. Tente salvar a mesma rotina novamente.' });
        },
      });
  }

  private blankDraft(enableMorning = false): Record<SkinPeriod, PeriodDraft> {
    return {
      MORNING: { enabled: enableMorning, title: SKIN_PERIODS[0].title, weekdays: [], steps: [this.step('')] },
      NIGHT: { enabled: false, title: SKIN_PERIODS[1].title, weekdays: [], steps: [this.step('')] },
    };
  }

  private step(text: string) {
    return { id: ++this.nextStepId, text };
  }

  private focusHeading(): void {
    afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
  }

  private focusStep(period: SkinPeriod, id: number): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(
      '#skin-' + period + '-step-' + id)?.focus(), { injector: this.injector });
  }

  private showErrors(errors: Record<string, string>): void {
    this.errors.set(errors);
    afterNextRender(() => this.errorSummary()?.nativeElement.focus(), { injector: this.injector });
  }
}
