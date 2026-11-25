import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { postWithCsrf } from './csrf';
import { RouterLink } from '@angular/router';
import { PASSWORD_HINT, PASSWORD_PATTERN } from './password-rules';

@Component({
  selector: 'app-password-change',
  imports: [RouterLink],
  styles: `
    :host { display: block; }
    form { margin: 0; }
    .password-actions { display: flex; flex-wrap: wrap; gap: var(--space-sm); }
  `,
  template: `
      <form method="post" (submit)="$event.preventDefault(); change(current, factor, password)">
        <div class="field">
          <label for="change-current-password">Senha atual</label>
          <input #current id="change-current-password" type="password" autocomplete="current-password" required>
        </div>
        <div class="field">
          <label for="change-factor">Código do autenticador</label>
          <input #factor id="change-factor" inputmode="numeric" autocomplete="one-time-code"
                 required pattern="[0-9]{6}" maxlength="6">
        </div>
        <div class="field">
          <label for="change-new-password">Nova senha</label>
          <input #password id="change-new-password" type="password" autocomplete="new-password" required
                 minlength="8" maxlength="256" [pattern]="passwordPattern" aria-describedby="new-password-hint">
          <p class="hint" id="new-password-hint">{{ passwordHint }}</p>
        </div>
        <div class="password-actions">
          <button class="primary" type="submit" [disabled]="pending()">{{ pending() ? 'Alterando…' : 'Alterar senha' }}</button>
          @if (!pending()) { <a class="secondary" routerLink="/perfil">Voltar ao perfil</a> }
        </div>
      </form>
      @if (error(); as message) {
        <p class="field-error" role="alert">{{ message }}</p>
      }
  `,
})
export class PasswordChange {
  protected readonly passwordHint = PASSWORD_HINT;
  protected readonly passwordPattern = PASSWORD_PATTERN;
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  readonly changed = output<void>();
  readonly sessionExpired = output<void>();
  protected readonly pending = signal(false);
  protected readonly error = signal('');

  protected change(current: HTMLInputElement, factor: HTMLInputElement, password: HTMLInputElement): void {
    if (this.pending()) {
      return;
    }
    const body = { currentPassword: current.value, factor: factor.value, password: password.value };
    current.value = '';
    factor.value = '';
    password.value = '';
    this.pending.set(true);
    this.error.set('');
    postWithCsrf<void>(this.http, '/api/auth/password/change', body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.changed.emit(),
      error: (error: HttpErrorResponse) => {
        this.pending.set(false);
        if (error.status === 401) {
          this.sessionExpired.emit();
        } else {
          this.error.set(error.error?.errors?.password ?? error.error?.errors?.body
            ?? (error.status === 429 ? 'Muitas tentativas. Aguarde alguns minutos.'
              : error.status === 403 ? 'Confira a senha atual e use um código novo do autenticador.'
              : 'Não foi possível alterar a senha.'));
        }
      },
    });
  }
}
