import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, computed, DestroyRef, ElementRef, inject, Injector, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CATEGORY_ICONS, CATEGORY_LABELS, STATUS_ICONS, STATUS_LABELS, careDetails } from './care-labels';
import type { CareDetails } from './care-labels';
import { CategoryFilter } from './category-filter';
import { SkinRoutineSteps } from './skin-routine-steps';
import { postWithCsrf } from './csrf';
import { formatHairDate } from './hair-preview-draft';

interface HistoryItem extends CareDetails {
  id: number;
  title: string;
  status: 'COMPLETED' | 'SKIPPED';
  actionDate: string;
  canUndo: boolean;
}

const month = new Intl.DateTimeFormat('pt-BR', { month: 'long', timeZone: 'UTC' });
const shortDate = new Intl.DateTimeFormat('pt-BR', { day: 'numeric', month: 'short', timeZone: 'UTC' });

@Component({
  selector: 'app-history',
  imports: [CategoryFilter, SkinRoutineSteps],
  templateUrl: './history.html',
})
export class History {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly feedback = viewChild.required<ElementRef<HTMLParagraphElement>>('feedback');
  private readonly errorFeedback = viewChild<ElementRef<HTMLParagraphElement>>('errorFeedback');
  private readonly records = signal<HistoryItem[]>([]);
  private readonly monthCounts = signal<{ completed: number; skipped: number } | null>(null);

  readonly recorded = input(false);
  readonly today = input.required<string>();
  readonly sessionExpired = output();
  protected readonly category = signal('');
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly moreFailed = signal(false);
  protected readonly nextPage = signal<number | null>(null);
  protected readonly failed = signal(false);
  protected readonly undoing = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly actionError = signal('');
  protected readonly categoryIcons = CATEGORY_ICONS;
  protected readonly categoryLabels = CATEGORY_LABELS;
  protected readonly statusIcons = STATUS_ICONS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly dateLabel = formatHairDate;
  protected readonly monthLabel = computed(() => month.format(new Date(this.today() + 'T00:00:00Z')));
  protected readonly actionDateLabel = (date: string) => date === this.today() ? 'Hoje'
    : shortDate.format(new Date(date + 'T00:00:00Z'));
  protected readonly itemDetails = careDetails;
  protected readonly visibleRecords = computed(() =>
    this.records().filter(item => !this.category() || item.category === this.category()));
  protected readonly monthly = computed(() => {
    const counts = this.monthCounts();
    if (counts) return { ...counts, total: counts.completed + counts.skipped };
    const prefix = this.today().slice(0, 7);
    const entries = this.records().filter(item => item.actionDate.startsWith(prefix));
    const completed = entries.filter(item => item.status === 'COMPLETED').length;
    return { total: entries.length, completed, skipped: entries.length - completed };
  });

  constructor() {
    this.load();
    afterNextRender(() => {
      if (this.recorded()) {
        this.feedback().nativeElement.focus();
      }
    });
  }

  protected load(page = 0): void {
    if (this.loading() || this.loadingMore()) return;
    this.loading.set(page === 0);
    this.loadingMore.set(page > 0);
    this.moreFailed.set(false);
    if (page === 0) this.failed.set(false);
    this.http.get<HistoryItem[]>(page ? `/api/history?page=${page}` : '/api/history', { observe: 'response' })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: response => {
        const records = response.body ?? [];
        this.records.set(page ? [...this.records(), ...records] : records);
        const next = response.headers.get('X-Next-Page');
        this.nextPage.set(next === null ? null : Number(next));
        const completed = response.headers.get('X-History-Completed');
        const skipped = response.headers.get('X-History-Skipped');
        this.monthCounts.set(completed === null || skipped === null ? null
          : { completed: Number(completed), skipped: Number(skipped) });
        this.loading.set(false);
        this.loadingMore.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        this.loadingMore.set(false);
        if (error.status === 401) {
          this.sessionExpired.emit();
        } else {
          if (page) this.moreFailed.set(true);
          else this.failed.set(true);
        }
      },
    });
  }

  protected undo(item: HistoryItem): void {
    if (this.undoing() || this.loadingMore() || !item.canUndo) {
      return;
    }
    this.notice.set('');
    this.actionError.set('');
    this.undoing.set(true);
    postWithCsrf(this.http, `/api/occurrences/${item.id}/undo`, null)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          this.undoing.set(false);
          this.notice.set('Cuidado voltou a ser planejado.');
          this.load();
          this.focusFeedback();
        },
        error: (error: HttpErrorResponse) => {
          this.undoing.set(false);
          if (error.status === 401) {
            this.sessionExpired.emit();
          } else if (error.status === 404 || error.status === 409) {
            this.actionError.set('O cuidado não está mais disponível para desfazer.');
            this.load();
            this.focusFeedback();
          } else {
            this.actionError.set('Não foi possível desfazer. Tente novamente.');
          }
        },
      });
  }

  private focusFeedback(): void {
    afterNextRender(() => (this.errorFeedback() ?? this.feedback()).nativeElement.focus(), { injector: this.injector });
  }
}
