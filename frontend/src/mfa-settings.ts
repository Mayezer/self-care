import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { postWithCsrf } from './csrf';
import { IdentityFlow, MfaStatus } from './identity-flow';

@Component({
  selector: 'app-mfa-settings',
  imports: [IdentityFlow],
  styles: `
    :host { display: block; min-width: 0; grid-column: 1 / -1; }
    section { display: grid; gap: var(--space-md); }
    .security-summary, .security-title { display: flex; gap: var(--space-sm); align-items: flex-start; }
    .security-summary { justify-content: space-between; flex-wrap: wrap; }
    .security-title { flex: 1 1 16rem; min-width: 0; }
    .security-title h2 { margin: 0; }
    .security-summary .pill { white-space: nowrap; }
    .security-content { display: grid; gap: var(--space-md); max-width: 42rem; }
    .security-content > p { margin: 0; }
    form { margin: 0; }
    .security-actions { display: flex; flex-wrap: wrap; gap: var(--space-sm); }
    .security-actions > button { width: auto; }
  `,
  template: `
    <section class="card" aria-labelledby="mfa-settings-title">
      <div class="security-summary">
        <div class="security-title">
          <span class="editorial-icon material-symbol" aria-hidden="true">lock</span>
          <h2 id="mfa-settings-title">Verificação em duas etapas</h2>
        </div>
        @if (state(); as current) {
          <span class="pill">{{ current.enabled ? 'Ativada' : 'Desativada' }}</span>
        }
      </div>
      <div class="security-content">
        @if (state(); as current) {
          @if (!current.available) {
            <p class="hint">A verificação em duas etapas está indisponível no momento.</p>
          } @else if (enrolling()) {
            <app-identity-flow initial="enroll-confirm" context="profile"
              (cancelled)="cancelEnrollment()" (authenticated)="changed.emit('enabled')" />
          } @else {
            <p class="hint">{{ current.enabled
              ? 'Ao entrar, você confirma sua senha e um código do aplicativo autenticador.'
              : 'Opcional: adicione um código do aplicativo autenticador à senha da sua conta.' }}</p>
            @if (current.required && current.enabled) {
              <p class="hint">Esta conta administrativa exige verificação em duas etapas.</p>
            } @else if (editing()) {
              <form method="post" (submit)="$event.preventDefault(); submit(currentPassword, $event)">
                <div class="field">
                  <label for="mfa-current-password">Senha atual</label>
                  <input #currentPassword id="mfa-current-password" name="currentPassword" type="password"
                         autocomplete="current-password" required>
                </div>
                @if (current.enabled) {
                  <div class="field">
                    <label for="mfa-disable-code">Código do autenticador</label>
                    <input id="mfa-disable-code" name="code" inputmode="numeric" autocomplete="one-time-code"
                           required pattern="[0-9]{6}" maxlength="6">
                  </div>
                  <p class="hint">Depois de desativar, você entra com a senha. As sessões atuais serão encerradas.</p>
                } @else {
                  <p class="hint">Enviaremos um código ao e-mail da sua conta para confirmar a configuração.</p>
                }
                <div class="security-actions">
                  <button class="primary" type="submit" [disabled]="pending()">{{ pending() ? 'Aguarde…'
                    : current.enabled ? 'Desativar verificação' : 'Enviar código de confirmação' }}</button>
                  <button class="secondary" type="button" [disabled]="pending()" (click)="closeEditor()">Cancelar</button>
                </div>
              </form>
            } @else {
              <div class="security-actions">
                <button class="secondary" type="button" (click)="editing.set(true)">
                  {{ current.enabled ? 'Desativar verificação' : 'Ativar verificação' }}
                </button>
              </div>
            }
          }
        } @else if (!error()) {
          <p role="status">Carregando segurança da conta…</p>
        }
        @if (error(); as message) {
          <p class="field-error" role="alert">{{ message }}</p>
          @if (!state()) { <button class="secondary" type="button" (click)="load()">Tentar novamente</button> }
        }
      </div>
    </section>
  `,
})
export class MfaSettings {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionExpired = output<void>();
  readonly changed = output<'enabled' | 'disabled'>();
  protected readonly state = signal<MfaStatus | null>(null);
  protected readonly editing = signal(false);
  protected readonly enrolling = signal(false);
  protected readonly pending = signal(false);
  protected readonly error = signal('');

  constructor() { this.load(); }

  protected load(): void {
    this.error.set('');
    this.http.get<MfaStatus>('/api/auth/mfa').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: state => this.state.set(state),
      error: (error: HttpErrorResponse) => this.failed(error, 'Não foi possível carregar a segurança da conta.'),
    });
  }

  protected closeEditor(): void {
    this.editing.set(false);
    this.error.set('');
  }

  protected submit(password: HTMLInputElement, event: SubmitEvent): void {
    const currentPassword = password.value;
    const form = event.target as HTMLFormElement;
    const code = form.elements.namedItem('code') as HTMLInputElement | null;
    const factor = code?.value ?? '';
    password.value = '';
    if (code) code.value = '';
    if (this.state()?.enabled) {
      this.post('disable', { currentPassword, code: factor }, () => this.changed.emit('disabled'));
    } else {
      this.post('setup', { currentPassword }, () => {
        this.editing.set(false);
        this.enrolling.set(true);
      });
    }
  }

  protected cancelEnrollment(): void {
    this.post('cancel', {}, () => this.enrolling.set(false));
  }

  private post(path: string, body: object, success: () => void): void {
    if (this.pending()) return;
    this.pending.set(true);
    this.error.set('');
    postWithCsrf<void>(this.http, '/api/auth/mfa/' + path, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.pending.set(false); success(); },
      error: (error: HttpErrorResponse) => {
        this.pending.set(false);
        this.failed(error, error.status === 403
          ? this.state()?.enabled ? 'Confira a senha atual e um código novo do autenticador.' : 'Confira a senha atual.'
          : error.status === 429 ? 'Muitas tentativas. Aguarde alguns minutos.'
          : 'Não foi possível alterar a verificação em duas etapas. Tente novamente.');
      },
    });
  }

  private failed(error: HttpErrorResponse, message: string): void {
    if (error.status === 401) this.sessionExpired.emit();
    else this.error.set(message);
  }
}
