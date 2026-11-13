import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { postWithCsrf } from './csrf';

@Component({
  selector: 'app-password-change',
  template: `
    <details>
      <summary>Alterar senha</summary>
      <p>Confirme sua senha atual e um código do autenticador. A troca encerra as sessões da sua conta.</p>
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
          <input #password id="change-new-password" type="password" autocomplete="new-password" required>
          <p class="hint">Use de 15 a 128 caracteres.</p>
        </div>
        <button class="primary" type="submit" [disabled]="pending()">Alterar senha</button>
      </form>
      @if (error(); as message) {
        <p class="field-error" role="alert">{{ message }}</p>
      }
    </details>
  `,
})
export class PasswordChange {
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
