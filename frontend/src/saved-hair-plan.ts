import { PageHeading } from './page-heading';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, EMPTY, switchMap } from 'rxjs';
import { postWithCsrf } from './csrf';
import { formatHairDate, STAGES } from './hair-preview-draft';
import type { PreviewOccurrence } from './hair-preview-draft';
import { HairRoutine, type HairRoutineData } from './hair-routine';

interface SavedPlan {
  planId: number;
  occurrences: PreviewOccurrence[];
  closedOn: string | null;
  routine?: HairRoutineData | null;
}

@Component({
  selector: 'app-saved-hair-plan',
  imports: [PageHeading, RouterLink, HairRoutine],
  styles: `
    .session {
      display: flex;
      flex-wrap: wrap;
      gap: .25rem .75rem;
    }
  `,
  template: `
    <div class="page-width page-content reading-page">
      <header appPageHeading="bookmark_add">
        <p class="eyebrow">Cuidado com o cabelo</p>
        <h1 #heading tabindex="-1">Cronograma salvo</h1>
      </header>
      @if (plan(); as saved) {
        <section class="card" aria-labelledby="manage-plan-title">
          <h2 id="manage-plan-title">Gerenciar cronograma</h2>
          @if (saved.closedOn; as date) {
            <p #closedStatus class="agenda-feedback" role="status" tabindex="-1">
              Cronograma encerrado em <time [attr.datetime]="date">{{ dateLabel(date) }}</time>.
            </p>
            <p class="preview-action"><a routerLink="/agenda">Ver minha agenda</a></p>
          } @else {
            <button #openButton class="secondary" type="button" [attr.aria-expanded]="confirming()"
                    [disabled]="closing()" (click)="openConfirmation()">Encerrar cronograma</button>
            @if (confirming()) {
              <div class="agenda-section" role="group" aria-labelledby="close-title" aria-describedby="close-description">
                <h3 id="close-title">Confirma o encerramento?</h3>
                <p id="close-description">Os cuidados ainda planejados para hoje e para os próximos dias sairão da agenda.
                  Os atrasos anteriores a hoje e o histórico serão mantidos. Depois de encerrar, não será possível reagendar.</p>
                @if (closeError(); as message) {
                  <p class="field-error" role="alert">{{ message }}</p>
                }
                <div class="agenda-actions">
                  <button #cancelButton class="secondary" type="button" [disabled]="closing()"
                          (click)="cancelConfirmation()">Cancelar</button>
                  <button class="primary" type="button" [disabled]="closing()" (click)="close(saved)">
                    {{ closing() ? 'Encerrando…' : 'Confirmar encerramento' }}
                  </button>
                </div>
              </div>
            }
          }
        </section>
      }
      @if (plan()?.routine; as routine) {
        <section class="card agenda-section"><app-hair-routine [routine]="routine" view="summary" /></section>
      }
      <section class="card agenda-section" aria-labelledby="saved-sessions">
        <h2 id="saved-sessions">Sessões do cronograma</h2>
        @if (plan(); as saved) {
          <ol class="sessions" role="list">
            @for (session of saved.occurrences; track session.date; let first = $first) {
              <li class="session">
                <time [attr.datetime]="session.date">{{ dateLabel(session.date) }}</time>
                <span>{{ stageLabel(session.stage) }}</span>
                @if (saved.routine; as routine) {
                  <details class="routine-details" [open]="first">
                    <summary>Rotina deste dia</summary>
                    <app-hair-routine [routine]="routine" />
                  </details>
                }
              </li>
            }
          </ol>
          @if (!saved.closedOn) {
            <p class="preview-action"><a routerLink="/agenda">Ver minha agenda</a></p>
          }
          <p><a routerLink="/">Criar outro cronograma</a></p>
        } @else if (error(); as message) {
          <p class="field-error" role="alert">{{ message }}</p>
          <p><a routerLink="/agenda">Ir para minha conta</a></p>
        } @else {
          <p role="status">Carregando as sessões…</p>
        }
      </section>
    </div>
  `,
})
export class SavedHairPlan {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild.required<ElementRef<HTMLHeadingElement>>('heading');
  private readonly openButton = viewChild<ElementRef<HTMLButtonElement>>('openButton');
  private readonly cancelButton = viewChild<ElementRef<HTMLButtonElement>>('cancelButton');
  private readonly closedStatus = viewChild<ElementRef<HTMLParagraphElement>>('closedStatus');
  protected readonly plan = signal<SavedPlan | null>(null);
  protected readonly error = signal('');
  protected readonly confirming = signal(false);
  protected readonly closing = signal(false);
  protected readonly closeError = signal('');
  protected readonly dateLabel = formatHairDate;

  constructor() {
    inject(ActivatedRoute).paramMap.pipe(
      switchMap(params => {
        this.plan.set(null);
        this.error.set('');
        this.confirming.set(false);
        this.closing.set(false);
        this.closeError.set('');
        const planId = encodeURIComponent(params.get('planId') ?? '');
        return this.http.get<SavedPlan>(`/api/hair-plans/${planId}`).pipe(
          catchError((error: HttpErrorResponse) => {
            this.error.set(error.status === 401 ? 'Entre na sua conta para abrir o cronograma.'
              : error.status === 404 ? 'Cronograma não encontrado.'
                : 'Não foi possível carregar o cronograma. Recarregue a página para tentar novamente.');
            return EMPTY;
          }),
        );
      }),
      takeUntilDestroyed(),
    ).subscribe(plan => this.plan.set(plan));
    afterNextRender(() => this.heading().nativeElement.focus());
  }

  protected stageLabel(stage: PreviewOccurrence['stage']): string | undefined {
    return STAGES.find(item => item.value === stage)?.label;
  }

  protected openConfirmation(): void {
    if (!this.closing() && !this.confirming()) {
      this.confirming.set(true);
      afterNextRender(() => this.cancelButton()?.nativeElement.focus(), { injector: this.injector });
    }
  }

  protected cancelConfirmation(): void {
    this.confirming.set(false);
    this.closeError.set('');
    this.openButton()?.nativeElement.focus();
  }

  protected close(plan: SavedPlan): void {
    if (!this.confirming() || this.closing() || plan.closedOn) {
      return;
    }
    this.closing.set(true);
    this.closeError.set('');
    postWithCsrf<{ closedOn: string }>(this.http, `/api/hair-plans/${plan.planId}/close`, null)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: ({ closedOn }) => {
          if (this.plan() !== plan) return;
          this.plan.set({ ...plan, closedOn });
          this.closing.set(false);
          this.confirming.set(false);
          afterNextRender(() => this.closedStatus()?.nativeElement.focus(), { injector: this.injector });
        },
        error: (error: HttpErrorResponse) => {
          if (this.plan() !== plan) return;
          this.closing.set(false);
          this.closeError.set(error.status === 401 || error.status === 403
            ? 'Sua sessão não está disponível. Entre novamente pela agenda para encerrar o cronograma.'
            : error.status === 404 ? 'Cronograma não encontrado.'
              : 'Não foi possível encerrar o cronograma. Tente novamente.');
        },
      });
  }
}
