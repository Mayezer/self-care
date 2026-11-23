import { NgTemplateOutlet } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { postWithCsrf } from './csrf';
import { CATEGORY_ICONS, CATEGORY_LABELS, STATUS_ICONS, STATUS_LABELS, careDetails } from './care-labels';
import type { CareDetails } from './care-labels';
import { CategoryFilter } from './category-filter';
import { SkinRoutineSteps } from './skin-routine-steps';
import { formatHairDate, WEEKDAYS } from './hair-preview-draft';

type DateAction = 'complete' | 'reschedule';
type Action = DateAction | 'skip' | 'undo';
const ACTION_MESSAGES: Record<Exclude<Action, 'complete'>, string> = {
  reschedule: 'Cuidado reagendado.',
  skip: 'Cuidado pulado.',
  undo: 'Cuidado voltou a ser planejado.',
};
const monthLabel = new Intl.DateTimeFormat('pt-BR', { month: 'long', timeZone: 'UTC' });
const shortDate = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', timeZone: 'UTC' });

interface AgendaItem extends CareDetails {
  id: number;
  planId: number | null;
  title: string;
  date: string;
  status: keyof typeof STATUS_LABELS;
  completedOn: string | null;
  skippedOn: string | null;
  canReschedule: boolean;
  cleaningSeries?: boolean;
  reminderTime?: string | null;
}

interface Week {
  today: string;
  days: string[];
  previousWeek: string | null;
  nextWeek: string | null;
  items: AgendaItem[];
  overdue: AgendaItem[];
  nextCare: AgendaItem | null;
  completedCount: number;
  careStreakDays?: number | null;
  overdueNextPage?: number | null;
  plannedCount?: number;
}

interface DailyMessage {
  text: string;
  category: 'SKIN' | null;
}

@Component({
  selector: 'app-agenda-week',
  imports: [CategoryFilter, NgTemplateOutlet, RouterLink, SkinRoutineSteps],
  templateUrl: './agenda-week.html',
})
export class AgendaWeek {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly actionDate = viewChild<ElementRef<HTMLInputElement>>('actionDate');
  private readonly actionStatus = viewChild.required<ElementRef<HTMLParagraphElement>>('actionStatus');
  private readonly closeConfirmation = viewChild<ElementRef<HTMLParagraphElement>>('closeConfirmation');
  private readonly dateParam = inject(ActivatedRoute).snapshot.queryParamMap.get('date');
  private requestedDate: string | null = null;
  private requestedSelection?: string;

  readonly sessionExpired = output();
  readonly scheduleNotice = input('');
  protected readonly week = signal<Week | null>(null);
  protected readonly dailyMessage = signal<DailyMessage | null>(null);
  protected readonly selectedDate = signal('');
  protected readonly category = signal('');
  protected readonly loading = signal(false);
  protected readonly overdueLoading = signal(false);
  protected readonly overdueError = signal(false);
  protected readonly failed = signal(false);
  protected readonly dateEditor = signal<{ id: number; action: DateAction } | null>(null);
  protected readonly closingItem = signal<number | null>(null);
  protected readonly submitting = signal(false);
  protected readonly actionsUnavailable = computed(() => this.loading() || this.overdueLoading() || this.submitting() || this.failed());
  protected readonly actionNotice = signal('');
  protected readonly actionError = signal('');
  protected readonly dateError = signal('');
  protected readonly dateLabel = formatHairDate;
  protected readonly weekdays = WEEKDAYS;
  protected readonly categoryIcons = CATEGORY_ICONS;
  protected readonly categoryLabels = CATEGORY_LABELS;
  protected readonly statusIcons = STATUS_ICONS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly itemDetails = careDetails;
  protected readonly monthLabel = (date: string) => monthLabel.format(new Date(date + 'T00:00:00Z'));
  protected readonly shortDate = (date: string) => shortDate.format(new Date(date + 'T00:00:00Z'));
  protected readonly dailyItems = computed(() => this.week()?.items.filter(item =>
    item.date === this.selectedDate() && this.matchesCategory(item)) ?? []);
  protected readonly dailyGroups = computed(() => [
    { title: 'Manhã', icon: 'light_mode', items: this.dailyItems().filter(item => item.period === 'MORNING') },
    { title: 'Noite', icon: 'dark_mode', items: this.dailyItems().filter(item => item.period === 'NIGHT') },
    { title: 'Sem período', icon: 'schedule', items: this.dailyItems().filter(item => !item.period) },
  ].filter(group => group.items.length > 0));
  protected readonly categoriesByDay = computed(() => {
    const categories = new Map<string, Set<keyof typeof CATEGORY_LABELS>>();
    const week = this.week();
    for (const item of [...(week?.items ?? []), ...(week?.overdue ?? [])]) {
      const day = categories.get(item.date) ?? new Set<keyof typeof CATEGORY_LABELS>();
      day.add(item.category);
      categories.set(item.date, day);
    }
    return categories;
  });
  protected readonly weeklyPending = computed(() => {
    const week = this.week();
    if (!week) return 0;
    if (week.plannedCount !== undefined) return week.plannedCount;
    return week.items.filter(item => item.status === 'PLANNED').length
      + week.overdue.filter(item => week.days.includes(item.date)).length;
  });
  protected readonly weeklyProgress = computed(() => {
    const completed = this.week()?.completedCount ?? 0;
    const total = completed + this.weeklyPending();
    return total ? 100 * completed / total : 0;
  });
  protected readonly overdueItems = computed(() => this.week()?.overdue.filter(item => this.matchesCategory(item)) ?? []);
  protected readonly selectedDayHasOverdue = computed(() =>
    this.overdueItems().some(item => item.date === this.selectedDate()));

  constructor() {
    const date = this.dateParam && /^\d{4}-\d{2}-\d{2}$/.test(this.dateParam) ? this.dateParam : null;
    this.loadWeek(date, date ?? undefined);
  }

  ngOnInit(): void {
    if (this.scheduleNotice()) {
      this.actionNotice.set(this.scheduleNotice());
      afterNextRender(() => this.actionStatus().nativeElement.focus(), { injector: this.injector });
    }
  }

  protected loadWeek(date: string | null, selection?: string): void {
    if (this.loading() || this.submitting() || this.overdueLoading()) {
      return;
    }
    this.closeDateEditor();
    this.requestedDate = date;
    this.requestedSelection = selection;
    this.loading.set(true);
    this.failed.set(false);
    this.overdueError.set(false);
    this.http.get<Week>('/api/agenda/week', { params: date ? { date } : {} }).pipe(
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: week => {
        const newDay = this.week()?.today !== week.today;
        this.week.set(week);
        if (newDay) {
          this.loadDailyMessage(week.today);
        }
        this.selectedDate.set(selection && week.days.includes(selection)
          ? selection : week.days.includes(week.today) ? week.today : week.days[0]);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        if (error.status === 401) {
          this.sessionExpired.emit();
          return;
        }
        this.failed.set(true);
      },
    });
  }

  protected loadMoreOverdue(): void {
    const current = this.week();
    const page = current?.overdueNextPage;
    if (!current || page == null || this.actionsUnavailable()) return;
    this.overdueLoading.set(true);
    this.overdueError.set(false);
    this.http.get<Week>('/api/agenda/week', { params: { date: current.days[0], overduePage: page } })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: result => {
          this.overdueLoading.set(false);
          if (result.today !== current.today) {
            this.loadWeek(null);
            return;
          }
          this.week.set({ ...current, overdue: [...current.overdue, ...result.overdue], overdueNextPage: result.overdueNextPage });
        },
        error: (error: HttpErrorResponse) => {
          this.overdueLoading.set(false);
          if (error.status === 401) this.sessionExpired.emit();
          else this.overdueError.set(true);
        },
      });
  }

  private loadDailyMessage(today: string): void {
    this.dailyMessage.set(null);
    this.http.get<DailyMessage | null>('/api/agenda/daily-message').pipe(
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: message => {
        if (this.week()?.today === today) {
          this.dailyMessage.set(message);
        }
      },
      // The message is optional and a failed request must not interrupt the agenda.
      error: () => {},
    });
  }

  protected retry(): void {
    this.loadWeek(this.requestedDate, this.requestedSelection);
  }

  protected goToToday(): void {
    this.loadWeek(null);
  }

  protected selectDate(date: string): void {
    this.closeDateEditor();
    this.selectedDate.set(date);
  }

  protected selectCategory(category: string): void {
    this.closeDateEditor();
    this.category.set(category);
  }

  protected openDateEditor(id: number, action: DateAction): void {
    if (this.actionsUnavailable()) {
      return;
    }
    this.actionNotice.set('');
    this.actionError.set('');
    this.dateError.set('');
    this.closingItem.set(null);
    this.dateEditor.set({ id, action });
    afterNextRender(() => this.actionDate()?.nativeElement.focus(), { injector: this.injector });
  }

  protected closeDateEditor(): void {
    this.dateEditor.set(null);
    this.dateError.set('');
    this.closingItem.set(null);
  }

  protected cancelDateEditor(button: HTMLButtonElement): void {
    if (!this.submitting()) {
      this.closeDateEditor();
      button.focus();
    }
  }

  protected confirmPlanClosure(item: AgendaItem): void {
    if (!this.actionsUnavailable()) {
      this.closeDateEditor();
      this.actionNotice.set('');
      this.actionError.set('');
      this.closingItem.set(item.id);
      afterNextRender(() => this.closeConfirmation()?.nativeElement.focus(), { injector: this.injector });
    }
  }

  protected closeSkinPlan(item: AgendaItem): void {
    if (this.actionsUnavailable() || this.closingItem() !== item.id || !item.planId) {
      return;
    }
    this.submitting.set(true);
    this.actionError.set('');
    postWithCsrf(this.http, `/api/skin-plans/${item.planId}/close`, null)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          this.submitting.set(false);
          this.actionNotice.set('Rotina encerrada.');
          this.loadWeek(this.week()?.days[0] ?? null, this.selectedDate());
          afterNextRender(() => this.actionStatus().nativeElement.focus(), { injector: this.injector });
        },
        error: (error: HttpErrorResponse) => {
          this.submitting.set(false);
          if (error.status === 401) {
            this.sessionExpired.emit();
            return;
          }
          this.actionError.set('Não foi possível encerrar a rotina. Confira a agenda antes de tentar novamente.');
        },
      });
  }

  protected changeOccurrence(item: AgendaItem, action: Action, date?: string): void {
    if (this.actionsUnavailable()) {
      return;
    }
    this.actionNotice.set('');
    this.actionError.set('');
    this.dateError.set('');
    this.submitting.set(true);
    const dateField = action === 'complete' ? 'completedOn' : action === 'reschedule' ? 'plannedDate' : null;
    postWithCsrf(this.http, `/api/occurrences/${item.id}/${action}`, dateField ? { [dateField]: date } : null, 'response')
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: response => {
          this.submitting.set(false);
          if (action === 'complete') {
            const result = response.headers.get('X-Care-Completion');
            this.actionNotice.set(result === 'applied' ? `Cuidado concluído: ${item.title}. Um cuidado de cada vez.`
              : result === 'unchanged' ? 'Este cuidado já estava concluído.' : 'Cuidado atualizado.');
          } else {
            this.actionNotice.set(ACTION_MESSAGES[action]);
          }
          if (action === 'reschedule' && date) {
            this.loadWeek(date, date);
          } else {
            this.loadWeek(this.week()?.days[0] ?? null, this.selectedDate());
          }
          afterNextRender(() => this.actionStatus().nativeElement.focus(), { injector: this.injector });
        },
        error: (error: HttpErrorResponse) => {
          this.submitting.set(false);
          if (error.status === 401) {
            this.sessionExpired.emit();
            return;
          }
          const dateError = dateField && error.error?.errors?.[dateField];
          const editor = this.dateEditor();
          if (editor?.id === item.id && editor.action === action && typeof dateError === 'string' && dateError) {
            this.dateError.set(dateError);
            afterNextRender(() => this.actionDate()?.nativeElement.focus(), { injector: this.injector });
            return;
          }
          if (error.status === 404 || error.status === 409) {
            const statusError = error.error?.errors?.status;
            this.actionError.set(error.status === 409 && typeof statusError === 'string'
              ? statusError : 'O cuidado não está mais disponível para esta ação.');
            this.loadWeek(this.week()?.days[0] ?? null, this.selectedDate());
            return;
          }
          this.actionError.set('Não foi possível registrar a ação. Tente novamente.');
        },
      });
  }

  private matchesCategory(item: AgendaItem): boolean {
    return !this.category() || this.category() === item.category;
  }
}
