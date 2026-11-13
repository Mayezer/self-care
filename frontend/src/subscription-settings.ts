import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, DOCUMENT, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { postWithCsrf } from './csrf';

const endpoint = '/api/subscription';
const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

interface SubscriptionState {
  active: boolean;
  canManage: boolean;
  accessUntil: string | null;
  cancelAtPeriodEnd: boolean;
  monthlyPriceCents: number;
  testMode: boolean;
}

@Component({
  selector: 'app-subscription-settings',
  imports: [RouterLink],
  templateUrl: './subscription-settings.html',
  styles: [`
    :host { display: grid; gap: var(--space-md); margin-top: var(--space-md); }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-sm); }
  `],
})
export class SubscriptionSettings {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);
  readonly sessionExpired = output();
  protected readonly state = signal<SubscriptionState | null>(null);
  protected readonly pending = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.load();
  }

  protected load(refresh = false): void {
    this.pending.set(true);
    this.error.set('');
    const request = refresh ? postWithCsrf<SubscriptionState>(this.http, endpoint + '/refresh', null)
      : this.http.get<SubscriptionState>(endpoint);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => {
        this.state.set(value);
        this.pending.set(false);
      },
      error: (error: HttpErrorResponse) => this.showError(error, 'Não foi possível consultar a assinatura.'),
    });
  }

  protected open(action: 'checkout' | 'portal'): void {
    if (this.pending()) return;
    this.pending.set(true);
    this.error.set('');
    postWithCsrf<{ url: string }>(this.http, endpoint + '/' + action, null)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: ({ url }) => this.document.defaultView?.location.assign(url),
        error: (error: HttpErrorResponse) => this.showError(error, error.status === 409
          ? 'A assinatura mudou. Atualize o status e tente novamente.'
          : 'Não foi possível abrir o Stripe. Tente novamente.'),
      });
  }

  protected price(cents: number): string {
    return currency.format(cents / 100);
  }

  protected date(value: string): string {
    return new Intl.DateTimeFormat('pt-BR').format(new Date(value));
  }

  private showError(error: HttpErrorResponse, message: string): void {
    this.pending.set(false);
    if (error.status === 401) {
      this.sessionExpired.emit();
      return;
    }
    this.error.set(message);
  }
}
