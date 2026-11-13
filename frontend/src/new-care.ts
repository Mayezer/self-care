import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, Injector, afterNextRender, inject, input, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CATEGORY_ICONS, CATEGORY_LABELS } from './care-labels';
import { postWithCsrf } from './csrf';
import { WEEKDAYS } from './hair-preview-draft';
import type { Weekday } from './hair-preview-draft';

type Period = 'MORNING' | 'NIGHT' | '';
type Category = 'SKIN' | 'MAKEUP' | 'NAILS';
type Field = 'date' | 'title' | 'period' | 'weeks' | 'weekdays' | 'note' | 'polishColor' | 'polishProduct';
type FieldErrors = Partial<Record<Field, string>>;

const CATEGORIES = {
  SKIN: { description: 'Rotina facial e hidratação', endpoint: 'skin',
    title: 'Título do cuidado facial', placeholder: 'Ex.: Hidratação facial' },
  MAKEUP: { description: 'Visuais e experimentações', endpoint: 'makeup',
    title: 'Título do visual ou prática', placeholder: 'Ex.: Maquiagem leve' },
  NAILS: { description: 'Esmaltação e cuidado das unhas', endpoint: 'nails',
    title: 'Título do cuidado de unhas', placeholder: 'Ex.: Trocar o esmalte' },
} satisfies Record<Category, { description: string; endpoint: string; title: string; placeholder: string }>;

@Component({
  selector: 'app-new-care',
  imports: [RouterLink],
  templateUrl: './new-care.html',
})
export class NewCare {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild.required<ElementRef<HTMLHeadingElement>>('heading');
  private readonly dateField = viewChild.required<ElementRef<HTMLInputElement>>('careDate');
  private readonly titleField = viewChild.required<ElementRef<HTMLInputElement>>('title');
  private readonly firstPeriod = viewChild<ElementRef<HTMLInputElement>>('firstPeriod');
  private readonly noteField = viewChild<ElementRef<HTMLTextAreaElement>>('note');
  private readonly colorField = viewChild<ElementRef<HTMLInputElement>>('polishColor');
  private readonly productField = viewChild<ElementRef<HTMLInputElement>>('polishProduct');
  private readonly weeksField = viewChild<ElementRef<HTMLInputElement>>('weeks');
  private readonly weekdayFields = viewChild<ElementRef<HTMLFieldSetElement>>('weekdayFields');
  private lastPlanRequest?: { key: string; id: string };

  readonly today = input.required<string>();
  protected readonly fromHistory = inject(ActivatedRoute).snapshot.queryParamMap.get('modo') === 'realizado';
  protected readonly direct = signal(this.fromHistory);
  protected readonly plannedDate = signal<string | null>(null);
  protected readonly completedOn = signal<string | null>(null);
  protected readonly categoryOptions = (Object.keys(CATEGORIES) as Category[]).map(value => ({
    value, label: CATEGORY_LABELS[value], description: CATEGORIES[value].description,
  }));
  protected readonly categories = CATEGORIES;
  protected readonly categoryLabels = CATEGORY_LABELS;
  protected readonly categoryIcons = CATEGORY_ICONS;
  protected readonly category = signal<Category>(this.fromHistory ? 'MAKEUP' : 'SKIN');
  protected readonly titles = signal<Record<Category, string>>({ SKIN: '', MAKEUP: '', NAILS: '' });
  protected readonly note = signal('');
  protected readonly polishColor = signal('');
  protected readonly polishProduct = signal('');
  protected readonly recurring = signal(false);
  protected readonly weekdays = WEEKDAYS;
  protected readonly selectedDays = signal<readonly Weekday[]>([]);
  protected readonly duration = signal('4');
  protected readonly period = signal<Period>('');
  protected readonly submitting = signal(false);
  protected readonly errors = signal<FieldErrors>({});
  protected readonly error = signal('');

  constructor() {
    afterNextRender(() => this.heading().nativeElement.focus());
  }

  protected clearField(field: Field): void {
    if (this.errors()[field]) {
      this.errors.update(errors => ({ ...errors, [field]: undefined }));
    }
  }

  protected choosePeriod(period: Period): void {
    this.period.set(period);
    this.clearField('period');
  }

  protected chooseCategory(category: Category): void {
    this.category.set(category);
    this.errors.set({});
    this.error.set('');
  }

  protected chooseIntent(direct: boolean): void {
    this.direct.set(direct);
    this.chooseCategory(direct && this.category() === 'SKIN' ? 'MAKEUP' : this.category());
  }

  protected updateDate(value: string): void {
    (this.direct() ? this.completedOn : this.plannedDate).set(value);
    this.clearField('date');
  }

  protected updateTitle(value: string): void {
    this.titles.update(titles => ({ ...titles, [this.category()]: value }));
    this.clearField('title');
  }

  protected chooseRecurrence(recurring: boolean): void {
    this.recurring.set(recurring);
    this.errors.set({});
    this.error.set('');
  }

  protected toggleDay(day: Weekday): void {
    this.selectedDays.update(days => days.includes(day) ? days.filter(value => value !== day) : [...days, day]);
    this.clearField('weekdays');
  }

  protected save(): void {
    if (this.submitting()) {
      return;
    }
    this.error.set('');
    const dateInput = this.dateField().nativeElement;
    const category = this.category();
    const title = this.titleField().nativeElement.value.trim();
    const date = dateInput.value;
    const direct = this.direct();
    const recurring = category === 'SKIN' && this.recurring();
    const weeks = this.weeksField()?.nativeElement.valueAsNumber ?? NaN;
    const weekdays = WEEKDAYS.map(day => day.value).filter(day => this.selectedDays().includes(day));
    const errors: FieldErrors = {};
    if (!date || dateInput.validity.badInput || !/^(?!0000)\d{4}-\d{2}-\d{2}$/.test(date)) {
      errors.date = direct ? 'Informe uma data de realização válida.'
        : recurring ? 'Informe uma data de início válida.' : 'Informe uma data planejada válida.';
    } else if (direct && dateInput.validity.rangeOverflow) {
      errors.date = 'Escolha a data de hoje ou uma data passada.';
    } else if (!direct && dateInput.validity.rangeUnderflow) {
      errors.date = 'Escolha a data de hoje ou uma data futura.';
    }
    if (!title) {
      errors.title = 'Informe o título do cuidado.';
    }
    if (recurring && (!Number.isInteger(weeks) || weeks < 1 || weeks > 12)) {
      errors.weeks = 'Escolha uma duração de 1 a 12 semanas.';
    }
    if (recurring && weekdays.length === 0) {
      errors.weekdays = 'Selecione pelo menos um dia da semana.';
    }
    this.errors.set(errors);
    if (this.focusError(errors)) {
      return;
    }

    const period = this.period() || null;
    let body: object = { title, ...(direct ? { completedOn: date } : { plannedDate: date }), ...(
      category === 'SKIN' ? { period } : category === 'MAKEUP' ? { note: this.note().trim() || null }
        : { polishColor: this.polishColor().trim() || null, polishProduct: this.polishProduct().trim() || null }
    ) };
    if (recurring) {
      const schedule = { title, period, startDate: date, weeks, weekdays };
      const key = JSON.stringify(schedule);
      if (this.lastPlanRequest?.key !== key) {
        this.lastPlanRequest = { key, id: crypto.randomUUID() };
      }
      body = { ...schedule, requestId: this.lastPlanRequest.id };
    }
    this.submitting.set(true);
    const endpoint = recurring ? '/api/skin-plans'
      : (direct ? '/api/history/' : '/api/occurrences/') + CATEGORIES[category].endpoint;
    postWithCsrf(this.http, endpoint, body)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          void this.router.navigate([direct ? '/historico' : '/agenda'], {
            queryParams: direct ? undefined : { date },
            info: direct ? 'care-recorded' : recurring ? 'skin-plan-scheduled' : 'care-scheduled', replaceUrl: true,
          });
        },
        error: (response: HttpErrorResponse) => {
          this.submitting.set(false);
          const fields = response.error?.errors;
          const dateError = direct ? fields?.completedOn : recurring ? fields?.startDate : fields?.plannedDate;
          const errors: FieldErrors = {
            date: typeof dateError === 'string' ? dateError : undefined,
            title: typeof fields?.title === 'string' ? fields.title : undefined,
            period: typeof fields?.period === 'string' ? fields.period : undefined,
            weeks: typeof fields?.weeks === 'string' ? fields.weeks : undefined,
            weekdays: typeof fields?.weekdays === 'string' ? fields.weekdays : undefined,
            note: typeof fields?.note === 'string' ? fields.note : undefined,
            polishColor: typeof fields?.polishColor === 'string' ? fields.polishColor : undefined,
            polishProduct: typeof fields?.polishProduct === 'string' ? fields.polishProduct : undefined,
          };
          this.errors.set(errors);
          if (!this.focusError(errors)) {
            this.error.set(response.status === 401
              ? 'Sua sessão terminou. Entre novamente para salvar um cuidado.'
              : direct ? 'Não foi possível salvar o cuidado. Confira o histórico antes de tentar novamente.'
                : 'Não foi possível salvar o cuidado. Confira a agenda antes de tentar novamente.');
          }
        },
      });
  }

  private focusError(errors: FieldErrors): boolean {
    const field = errors.date ? this.dateField().nativeElement
      : errors.title ? this.titleField().nativeElement
      : errors.period ? this.firstPeriod()?.nativeElement
      : errors.note ? this.noteField()?.nativeElement
      : errors.polishColor ? this.colorField()?.nativeElement
      : errors.polishProduct ? this.productField()?.nativeElement
      : errors.weeks ? this.weeksField()?.nativeElement
      : errors.weekdays ? this.weekdayFields()?.nativeElement.querySelector('button') : null;
    if (field) {
      afterNextRender(() => field.focus(), { injector: this.injector });
    }
    return Boolean(field);
  }
}
