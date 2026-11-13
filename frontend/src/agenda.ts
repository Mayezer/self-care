import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AgendaWeek } from './agenda-week';
import { postWithCsrf } from './csrf';
import { clearBrowserPush } from './web-push-settings';
import { readHairPreviewDraft } from './hair-preview-draft';
import { HairProfile } from './hair-profile';
import { HairProducts } from './hair-products';
import { HairDiary } from './hair-diary';
import { History } from './history';
import { Forum } from './forum';
import { NewCare } from './new-care';
import { ReminderSettings } from './reminder-settings';
import { SubscriptionSettings } from './subscription-settings';
import { AccountSessions } from './account-sessions';
import { IdentityFlow, type LoginStep } from './identity-flow';
import { PasswordChange } from './password-change';

interface AgendaAccount {
  username: string | null;
  email: string | null;
  timeZone: string;
  today: string;
  remindersAvailable: boolean;
  hairProfileAvailable: boolean;
  hairProductsAvailable: boolean;
  guidesAvailable: boolean;
  forumAvailable: boolean;
  billingAvailable: boolean;
  hairDiaryAvailable: boolean;
  registrationEnabled: boolean;
}

type FieldErrors = Partial<Record<'email' | 'code' | 'password' | 'body', string>>;
type AuthMode = 'login' | 'register' | 'confirm';

@Component({
  selector: 'app-agenda',
  imports: [AgendaWeek, HairDiary, HairProfile, HairProducts, History, Forum, NewCare, ReminderSettings,
    SubscriptionSettings, AccountSessions, IdentityFlow, PasswordChange, RouterLink],
  templateUrl: './agenda.html',
})
export class Agenda {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('heading');
  private readonly emailField = viewChild<ElementRef<HTMLInputElement>>('emailInput');
  private readonly codeField = viewChild<ElementRef<HTMLInputElement>>('codeInput');
  private readonly passwordField = viewChild<ElementRef<HTMLInputElement>>('passwordInput');

  private readonly view = inject(ActivatedRoute).snapshot.data['view'];
  protected readonly isAgenda = !this.view;
  protected readonly showHistory = this.view === 'history';
  protected readonly showNewCare = this.view === 'new-care';
  protected readonly showProfile = this.view === 'profile';
  protected readonly showForum = this.view === 'forum';
  protected readonly showHairProfile = this.view === 'hair-profile';
  protected readonly showHairProducts = this.view === 'hair-products';
  protected readonly showHairDiary = this.view === 'hair-diary';
  protected readonly showReminders = this.view === 'reminders';
  protected readonly showSubscription = this.view === 'subscription';
  protected readonly pageTitle = this.showHairProducts ? 'Produtos' : this.showHairDiary ? 'Diário capilar' : this.showSubscription ? 'Assinatura' : this.showForum ? 'Fórum' : this.showReminders ? 'Lembretes' : this.showHairProfile ? 'Meu cabelo'
    : this.showProfile ? 'Perfil' : this.showHistory ? 'Histórico' : this.showNewCare ? 'Novo cuidado' : 'Agenda';
  protected readonly accountArea = this.showHairProducts ? 'as sugestões de produtos' : this.showHairDiary ? 'o diário capilar' : this.showSubscription ? 'a assinatura' : this.showForum ? 'o fórum' : this.showReminders ? 'as configurações' : this.showHairProfile || this.showProfile
    ? 'o perfil' : this.showHistory ? 'o histórico' : 'a agenda';
  protected readonly careRecorded = this.router.currentNavigation()?.extras.info === 'care-recorded';
  protected readonly scheduleNotice = this.router.currentNavigation()?.extras.info === 'skin-plan-scheduled'
    ? 'Rotina salva na agenda.'
    : this.router.currentNavigation()?.extras.info === 'care-scheduled' ? 'Cuidado salvo na agenda.' : '';
  protected readonly hairPreviewDraft = this.view ? null : readHairPreviewDraft(this.router);
  protected readonly account = signal<AgendaAccount | null>(null);
  protected readonly checking = signal(true);
  protected readonly unavailable = signal(false);
  protected readonly pending = signal(false);
  protected readonly mode = signal<AuthMode>('login');
  protected readonly registrationEnabled = signal(true);
  protected readonly errors = signal<FieldErrors>({});
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly identity = signal<LoginStep | { mfa: 'reset'; needsEmail: false } | null>(null);

  constructor() {
    this.loadAgenda(this.showHairDiary || this.showSubscription || this.showProfile || this.showHairProfile || this.showHairProducts);
    if (this.hairPreviewDraft || this.view) {
      afterNextRender(() => this.heading()?.nativeElement.focus());
    }
  }

  protected loadAgenda(focusHeading = false): void {
    this.checking.set(true);
    this.unavailable.set(false);
    this.http.get<AgendaAccount>('/api/agenda').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: account => {
        this.registrationEnabled.set(account.registrationEnabled !== false);
        if (this.hairPreviewDraft) {
          void this.router.navigate(['/'], {
            state: { hairPreviewDraft: this.hairPreviewDraft },
            replaceUrl: true,
          });
          return;
        }
        this.account.set(account);
        this.checking.set(false);
        if (focusHeading && !this.showNewCare) {
          afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
        }
      },
      error: (error: HttpErrorResponse) => {
        this.account.set(null);
        if (error.status === 401) {
          this.registrationEnabled.set(error.headers.get('X-Registration-Enabled') !== 'false');
          if (!this.registrationEnabled()) {
            this.mode.set('login');
          }
        }
        this.unavailable.set(error.status !== 401);
        this.checking.set(false);
        if (focusHeading && this.showHairProducts) {
          afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
        }
      },
    });
  }

  protected switchMode(mode: AuthMode): void {
    this.mode.set(mode);
    this.clearMessages();
    this.focusAuthField(mode === 'confirm' ? 'code' : 'email');
  }

  protected sessionsEnded(message = 'Você saiu da conta.'): void {
    void clearBrowserPush().catch(() => undefined);
    this.account.set(null);
    this.notice.set(message);
    this.focusAuthField('email');
  }

  protected requestCode(email: string): void {
    this.send('register', { email }, () => {
      this.mode.set('confirm');
      this.notice.set('Se o e-mail puder ser cadastrado, você receberá um código válido por 10 minutos.');
      this.focusAuthField('code');
    });
  }

  protected login(identifier: string, password: HTMLInputElement): void {
    const body = new HttpParams().set('username', identifier).set('password', password.value);
    this.send('login', body, result => {
      password.value = '';
      if (result?.mfa) {
        this.identity.set(result);
      } else {
        this.identityAuthenticated();
      }
    });
  }

  protected requestPasswordRecovery(): void {
    this.clearMessages();
    this.identity.set({ mfa: 'reset', needsEmail: false });
  }

  protected closeIdentity(): void {
    this.identity.set(null);
    this.focusAuthField('email');
  }

  protected identityAuthenticated(): void {
    this.identity.set(null);
    void clearBrowserPush().catch(() => undefined);
    this.loadAgenda(true);
  }

  protected passwordReset(): void {
    this.closeIdentity();
    this.notice.set('Senha redefinida. Entre com a nova senha.');
  }

  protected confirm(email: string, code: HTMLInputElement, password: HTMLInputElement): void {
    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    this.send('confirm', { email, code: code.value, password: password.value, timeZone }, () => {
      code.value = '';
      password.value = '';
      this.mode.set('login');
      this.notice.set('E-mail confirmado. Entre com a senha que você criou.');
      this.focusAuthField('password');
    });
  }

  private send(action: 'register' | 'confirm' | 'login', body: object, success: (result: LoginStep | null) => void): void {
    if (this.pending()) {
      return;
    }
    this.clearMessages();
    this.pending.set(true);
    this.post(action, body).subscribe({
      next: result => {
        this.pending.set(false);
        success(result);
      },
      error: (error: HttpErrorResponse) => {
        const fields: FieldErrors | undefined = error.error?.errors;
        this.errors.set(fields ?? {});
        this.error.set(fields?.body ?? (fields
          ? 'Confira os campos indicados.'
          : error.status === 429 ? 'Muitas tentativas. Aguarde antes de tentar novamente.'
            : error.status === 401 ? 'Dados de entrada incorretos ou e-mail ainda não confirmado.'
              : 'Não foi possível continuar. Tente novamente.'));
        this.pending.set(false);
        const field = fields?.email ? 'email' : fields?.code ? 'code' : fields?.password ? 'password' : null;
        if (field) {
          this.focusAuthField(field);
        }
      },
    });
  }

  protected logout(): void {
    if (this.pending()) {
      return;
    }
    this.clearMessages();
    this.pending.set(true);
    this.post('logout', null).subscribe({
      next: () => {
        this.pending.set(false);
        this.sessionsEnded();
      },
      error: () => {
        this.error.set('Não foi possível sair da conta. Tente novamente.');
        this.pending.set(false);
      },
    });
  }

  private post(action: 'register' | 'confirm' | 'login' | 'logout', body: object | null) {
    return postWithCsrf<LoginStep | null>(this.http, `/api/auth/${action}`, body).pipe(
      takeUntilDestroyed(this.destroyRef),
    );
  }

  private focusAuthField(field: 'email' | 'code' | 'password'): void {
    afterNextRender(() => {
      const input = field === 'email' ? this.emailField() : field === 'code' ? this.codeField() : this.passwordField();
      input?.nativeElement.focus();
    }, { injector: this.injector });
  }

  private clearMessages(): void {
    this.error.set('');
    this.errors.set({});
    this.notice.set('');
  }
}
