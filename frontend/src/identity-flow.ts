import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { postWithCsrf } from './csrf';
import { PASSWORD_HINT, PASSWORD_PATTERN } from './password-rules';

export interface LoginStep {
  mfa: 'verify' | 'enroll-email';
}

type Step = LoginStep['mfa'] | 'enroll-confirm' | 'activate' | 'recover' | 'reset' | 'reset-complete'
  | 'reset-recover' | 'reset-activate';

interface Setup {
  secret: string;
  recovery: string;
}

@Component({
  selector: 'app-identity-flow',
  styles: `
    :host { display: grid; gap: var(--space-md); }
    form { margin-top: 0; }
    code { overflow-wrap: anywhere; user-select: all; }
    .identity-back { justify-self: start; }
  `,
  template: `
    @switch (step()) {
      @case ('enroll-email') {
        <h2 #heading tabindex="-1">Configure a verificação em duas etapas</h2>
        <p>Enviaremos um código ao e-mail da sua conta para você configurar o autenticador.</p>
        <form method="post" (submit)="$event.preventDefault(); sendEnrollmentEmail()">
          <button class="primary" type="submit" [disabled]="pending()">Enviar código</button>
        </form>
      }
      @case ('enroll-confirm') {
        <h2 #heading tabindex="-1">Confirme o e-mail</h2>
        <p>Digite o código enviado por e-mail. Ele vale por 10 minutos a partir do início da entrada.</p>
        <form method="post" (submit)="$event.preventDefault(); confirmEnrollmentEmail(code.value)">
          <div class="field">
            <label for="identity-email-code">Código do e-mail</label>
            <input #code id="identity-email-code" autocomplete="one-time-code" required maxlength="43" spellcheck="false">
          </div>
          <button class="primary" type="submit" [disabled]="pending()">Confirmar e-mail</button>
          <button class="secondary" type="button" [disabled]="pending()" (click)="sendEnrollmentEmail()">Reenviar código</button>
        </form>
      }
      @case ('verify') {
        <h2 #heading tabindex="-1">Verificação em duas etapas</h2>
        <form method="post" (submit)="$event.preventDefault(); verify(code.value)">
          <div class="field">
            <label for="identity-verify-code">Código do autenticador</label>
            <input #code id="identity-verify-code" inputmode="numeric" autocomplete="one-time-code"
                   required pattern="[0-9]{6}" maxlength="6">
          </div>
          <button class="primary" type="submit" [disabled]="pending()">Entrar</button>
          <button class="secondary" type="button" [disabled]="pending()" (click)="move('recover')">Usar chave de recuperação</button>
        </form>
      }
      @case ('recover') {
        <h2 #heading tabindex="-1">Recupere o autenticador</h2>
        <form method="post" (submit)="$event.preventDefault(); recoverLogin(key.value)">
          <div class="field">
            <label for="identity-recovery-key">Chave de recuperação</label>
            <input #key id="identity-recovery-key" type="password" autocomplete="off" required maxlength="43">
          </div>
          <button class="primary" type="submit" [disabled]="pending()">Configurar novo autenticador</button>
        </form>
      }
      @case ('reset') {
        <h2 #heading tabindex="-1">Recupere sua senha</h2>
        <form method="post" (submit)="$event.preventDefault(); requestReset()">
          <div class="field">
            <label for="identity-reset-email">E-mail da conta</label>
            <input id="identity-reset-email" type="email" autocomplete="email" required maxlength="254"
                   [value]="email()" (input)="updateEmail($event)">
          </div>
          <button class="primary" type="submit" [disabled]="pending()">Solicitar código</button>
        </form>
      }
      @case ('reset-complete') {
        <h2 #heading tabindex="-1">Redefina sua senha</h2>
        <p>Se o e-mail estiver cadastrado, você receberá um código válido por 10 minutos.</p>
        <form method="post" (submit)="$event.preventDefault(); reset(code.value, factor.value, password.value); password.value = ''">
          <div class="field">
            <label for="identity-reset-code">Código do e-mail</label>
            <input #code id="identity-reset-code" autocomplete="one-time-code" required maxlength="43" spellcheck="false">
          </div>
          <div class="field">
            <label for="identity-reset-factor">Código do autenticador, se já configurado</label>
            <input #factor id="identity-reset-factor" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6">
          </div>
          <div class="field">
            <label for="identity-reset-password">Nova senha</label>
            <input #password id="identity-reset-password" type="password" autocomplete="new-password" required
                   minlength="8" maxlength="256" [pattern]="passwordPattern" aria-describedby="identity-password-hint">
            <p class="hint" id="identity-password-hint">{{ passwordHint }}</p>
          </div>
          <button class="primary" type="submit" [disabled]="pending()">Redefinir senha</button>
          <button class="secondary" type="button" [disabled]="pending()" (click)="move('reset-recover')">Perdi o autenticador</button>
        </form>
      }
      @case ('reset-recover') {
        <h2 #heading tabindex="-1">Recupere a senha e o autenticador</h2>
        <form method="post" (submit)="$event.preventDefault(); recoverReset(code.value, key.value)">
          <div class="field">
            <label for="identity-recover-code">Código do e-mail</label>
            <input #code id="identity-recover-code" autocomplete="one-time-code" required maxlength="43" spellcheck="false">
          </div>
          <div class="field">
            <label for="identity-reset-key">Chave de recuperação</label>
            <input #key id="identity-reset-key" type="password" autocomplete="off" required maxlength="43">
          </div>
          <button class="primary" type="submit" [disabled]="pending()">Configurar novo autenticador</button>
        </form>
      }
    }
    @if (setup(); as current) {
      <h2 #heading tabindex="-1">Adicione a conta ao autenticador</h2>
      <p>No aplicativo autenticador, adicione uma conta por chave de configuração. Selecione códigos baseados em tempo.</p>
      <p>Chave de configuração <code>{{ current.secret }}</code></p>
      <p id="identity-activate-hint">Depois de adicionar a conta, digite abaixo os 6 dígitos que aparecem no aplicativo autenticador do seu celular.</p>
      <p>Guarde esta chave de recuperação em local seguro. Ela permite configurar outro autenticador se você perder o acesso ao atual.</p>
      <p>Chave de recuperação <code>{{ current.recovery }}</code></p>
      <form method="post" (submit)="$event.preventDefault(); activate(code.value, $event); code.value = ''">
        <div class="field">
          <label for="identity-activate-code">Código do novo autenticador</label>
          <input #code id="identity-activate-code" name="factor" inputmode="numeric" autocomplete="one-time-code"
                 aria-describedby="identity-activate-hint" required pattern="[0-9]{6}" maxlength="6">
        </div>
        @if (step() === 'reset-activate') {
          <div class="field">
            <label for="identity-recovery-password">Nova senha</label>
            <input id="identity-recovery-password" name="password" type="password" autocomplete="new-password" required
                   minlength="8" maxlength="256" [pattern]="passwordPattern" aria-describedby="identity-recovery-password-hint">
            <p class="hint" id="identity-recovery-password-hint">{{ passwordHint }}</p>
          </div>
        }
        <p><label><input type="checkbox" required> Guardei a chave de recuperação</label></p>
        <button class="primary" type="submit" [disabled]="pending()">Confirmar configuração</button>
      </form>
    }
    @if (error(); as message) {
      <p class="field-error" role="alert">{{ message }}</p>
    }
    <button class="secondary identity-back" type="button" [disabled]="pending()" (click)="cancelled.emit()">Voltar para a entrada</button>
  `,
})
export class IdentityFlow {
  protected readonly passwordHint = PASSWORD_HINT;
  protected readonly passwordPattern = PASSWORD_PATTERN;
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('heading');
  readonly initial = input.required<LoginStep['mfa'] | 'reset'>();
  readonly cancelled = output<void>();
  readonly authenticated = output<void>();
  readonly resetDone = output<void>();
  protected readonly step = linkedSignal<Step>(() => this.initial());
  protected readonly email = signal('');
  protected readonly pending = signal(false);
  protected readonly error = signal('');
  protected readonly setup = signal<Setup | null>(null);

  constructor() {
    this.focusHeading();
  }

  protected updateEmail(event: Event): void {
    this.email.set((event.target as HTMLInputElement).value);
  }

  protected move(step: Step): void {
    this.error.set('');
    this.step.set(step);
    this.focusHeading();
  }

  protected sendEnrollmentEmail(): void {
    this.post<void>('mfa/email', {}, () => this.move('enroll-confirm'));
  }

  protected confirmEnrollmentEmail(code: string): void {
    this.post<Setup>('mfa/email/confirm', { code }, setup => {
      this.setup.set(setup);
      this.move('activate');
    });
  }

  protected verify(code: string): void {
    this.post<void>('mfa/verify', { code }, () => this.authenticated.emit());
  }

  protected recoverLogin(code: string): void {
    this.post<Setup>('mfa/recover', { code }, setup => {
      this.setup.set(setup);
      this.move('activate');
    });
  }

  protected requestReset(): void {
    this.post<void>('password/reset/request', { email: this.email() }, () => this.move('reset-complete'));
  }

  protected reset(code: string, factor: string, password: string): void {
    this.post<void>('password/reset/complete', { email: this.email(), code, factor, password },
      () => this.resetDone.emit());
  }

  protected recoverReset(code: string, recovery: string): void {
    this.post<Setup>('password/reset/recovery', { email: this.email(), code, recovery }, setup => {
      this.setup.set(setup);
      this.move('reset-activate');
    });
  }

  protected activate(code: string, event: SubmitEvent): void {
    const form = event.target as HTMLFormElement;
    if (this.step() === 'reset-activate') {
      const input = form.elements.namedItem('password') as HTMLInputElement;
      const password = input.value;
      input.value = '';
      this.post<void>('password/reset/recovery/complete', { factor: code, password }, () => this.resetDone.emit());
    } else {
      this.post<void>('mfa/activate', { code }, () => this.authenticated.emit());
    }
  }

  private post<T>(path: string, body: object, success: (value: T) => void): void {
    if (this.pending()) {
      return;
    }
    this.pending.set(true);
    this.error.set('');
    postWithCsrf<T>(this.http, '/api/auth/' + path, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => {
        this.pending.set(false);
        success(value);
      },
      error: (error: HttpErrorResponse) => {
        this.pending.set(false);
        this.error.set(error.error?.errors?.password ?? error.error?.errors?.body
          ?? (error.status === 429 ? 'Muitas tentativas. Aguarde alguns minutos.'
            : error.status === 401 ? 'A verificação expirou. Volte para a entrada.'
            : error.status === 409 ? 'Este e-mail está indisponível para esta conta. Volte para a entrada e use outro.'
            : error.status === 400 ? 'Confira os dados e use um código atual do autenticador, se necessário.'
            : 'Não foi possível continuar. Tente novamente.'));
      },
    });
  }

  private focusHeading(): void {
    afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
  }
}
