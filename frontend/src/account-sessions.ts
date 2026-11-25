import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { postWithCsrf } from './csrf';
import { ACCOUNT_TIME_ZONE } from './account-time-zone';

interface Session {
  id: string;
  current: boolean;
  lastActivity: string;
}

@Component({
  selector: 'app-account-sessions',
  styles: `
    :host { display: block; }
    summary { display: flex; align-items: center; gap: var(--space-sm); min-height: var(--touch); cursor: pointer; list-style: none; }
    summary::-webkit-details-marker { display: none; }
    summary > .material-symbol:last-child { margin-inline-start: auto; transition: rotate .15s; }
    details[open] summary > .material-symbol:last-child { rotate: 180deg; }
    summary .hint { display: block; }
    ul { display: grid; gap: var(--space-sm); padding: 0; margin-block: var(--space-md); list-style: none; }
    li { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: var(--space-sm); padding: var(--space-md); border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--neutral); }
    .session-details { display: grid; gap: .25rem; }
    form { border-top: 1px solid var(--line); padding-top: var(--space-md); }
    .session-actions { display: flex; flex-wrap: wrap; gap: var(--space-sm); }
    @media (prefers-reduced-motion: reduce) { summary > .material-symbol:last-child { transition: none; } }
  `,
  template: `
    <details class="card" (toggle)="toggle(panel.open)" #panel>
      <summary>
        <span class="editorial-icon material-symbol" aria-hidden="true">devices</span>
        <span><strong>Sessões ativas</strong><span class="hint">Veja onde sua conta está conectada</span></span>
        <span class="material-symbol" aria-hidden="true">expand_more</span>
      </summary>
      @if (loading()) {
        <p role="status">Carregando sessões…</p>
      }
      @if (error(); as message) {
        <p class="field-error" role="alert">{{ message }}</p>
      }
      @if (notice(); as message) {
        <p role="status">{{ message }}</p>
      }
      @if (sessions().length) {
        <ul>
          @for (session of sessions(); track session.id) {
            <li>
              <div class="session-details">
                <strong>{{ session.current ? 'Este navegador' : 'Outra sessão' }}</strong>
                <p class="hint">Última atividade <time [attr.datetime]="session.lastActivity">{{ activity(session.lastActivity) }}</time></p>
              </div>
              <button class="secondary" type="button" [disabled]="pending()"
                      (click)="select(session.id, session.current)"
                      [attr.aria-label]="session.current ? 'Encerrar sessão deste navegador'
                        : 'Encerrar sessão com última atividade em ' + activity(session.lastActivity)">
                <span class="material-symbol" aria-hidden="true">logout</span>Encerrar sessão
              </button>
            </li>
          }
        </ul>
        <button class="secondary" type="button" [disabled]="pending()" (click)="select('all', true)">
          Encerrar todas as sessões
        </button>
      } @else if (!loading() && error()) {
        <button class="secondary" type="button" (click)="load()">Tentar novamente</button>
      }
      @if (target(); as selected) {
        <form method="post" (submit)="$event.preventDefault(); revoke(password)">
          <p>{{ selected.id === 'all' ? 'Confirme sua senha para sair de todos os dispositivos.'
            : 'Confirme sua senha para encerrar a sessão selecionada.' }}</p>
          <div class="field">
            <label for="revoke-session-password">Senha atual</label>
            <input #password id="revoke-session-password" type="password" autocomplete="current-password"
                   required maxlength="1024" [readOnly]="pending()">
          </div>
          <div class="session-actions">
            <button class="primary" type="submit" [disabled]="pending()">Confirmar encerramento</button>
            <button class="secondary" type="button" [disabled]="pending()" (click)="target.set(null)">Cancelar</button>
          </div>
        </form>
      }
    </details>
  `,
})
export class AccountSessions {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly password = viewChild<ElementRef<HTMLInputElement>>('password');
  private readonly format = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short', timeZone: ACCOUNT_TIME_ZONE });

  readonly signedOut = output<void>();
  readonly sessionExpired = output<void>();
  protected readonly sessions = signal<Session[]>([]);
  protected readonly target = signal<{ id: string; signsOut: boolean } | null>(null);
  protected readonly loading = signal(false);
  protected readonly pending = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');

  protected toggle(open: boolean): void {
    if (open && !this.pending() && !this.loading()) {
      this.load();
    }
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set('');
    this.http.get<Session[]>('/api/auth/sessions').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: sessions => {
        this.sessions.set(sessions);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        if (error.status === 401) {
          this.sessionExpired.emit();
        } else {
          this.error.set('Não foi possível consultar as sessões.');
        }
      },
    });
  }

  protected activity(value: string): string {
    return this.format.format(new Date(value));
  }

  protected select(id: string, signsOut: boolean): void {
    this.error.set('');
    this.notice.set('');
    const field = this.password()?.nativeElement;
    if (field) {
      field.value = '';
    }
    this.target.set({ id, signsOut });
    afterNextRender(() => this.password()?.nativeElement.focus(), { injector: this.injector });
  }

  protected revoke(password: HTMLInputElement): void {
    const selected = this.target();
    if (!selected || this.pending()) {
      return;
    }
    const value = password.value;
    password.value = '';
    this.error.set('');
    this.pending.set(true);
    postWithCsrf<void>(this.http, '/api/auth/sessions/revoke', { session: selected.id, password: value })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          this.pending.set(false);
          this.target.set(null);
          if (selected.signsOut) {
            this.signedOut.emit();
          } else {
            this.notice.set('Sessão encerrada.');
            this.load();
          }
        },
        error: (error: HttpErrorResponse) => {
          this.pending.set(false);
          if (error.status === 401) {
            this.sessionExpired.emit();
          } else if (error.status === 404) {
            this.target.set(null);
            this.notice.set('Essa sessão já foi encerrada.');
            this.load();
          } else {
            this.error.set(error.status === 403 ? 'Confira a senha e tente novamente.'
              : error.status === 429 ? 'Muitas tentativas. Aguarde antes de tentar novamente.'
              : 'Não foi possível encerrar a sessão.');
            password.focus();
          }
        },
      });
  }
}
