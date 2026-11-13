import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { putWithCsrf } from './csrf';

const URL = '/api/agenda/web-push';

interface Settings {
  publicKey: string;
  active: boolean;
}

function registration(): Promise<ServiceWorkerRegistration | undefined> {
  return 'serviceWorker' in navigator ? navigator.serviceWorker.getRegistration('/') : Promise.resolve(undefined);
}

export async function clearBrowserPush(): Promise<void> {
  const worker = await registration();
  await Promise.all([
    worker?.pushManager.getSubscription().then(subscription => subscription?.unsubscribe()),
    worker?.getNotifications().then(notifications => notifications.forEach(notification => notification.close())),
  ]);
}

@Component({
  selector: 'app-web-push-settings',
  template: `
    <section class="card reminder-form" aria-labelledby="push-heading">
      <h2 id="push-heading">Notificações do navegador</h2>
      <p class="hint">Este navegador usa as categorias e o horário salvos acima. A notificação leva à Agenda.</p>
      <p role="status">{{ message() }}</p>
      @if (active()) {
        <button class="secondary" type="button" (click)="disable()" [disabled]="busy()">Desativar notificações</button>
      } @else if (publicKey() && permission() !== 'denied') {
        <button class="secondary" type="button" (click)="enable()" [disabled]="busy()">Ativar notificações</button>
      }
      @if (loadFailed()) {
        <button class="secondary" type="button" (click)="load()" [disabled]="busy()">Tentar carregar notificações</button>
      }
    </section>
  `,
  styleUrl: './reminder-settings.css',
  styles: [':host { display: block; margin-block-start: var(--space-md); }'],
})
export class WebPushSettings {
  private readonly http = inject(HttpClient);
  readonly sessionExpired = output();
  protected readonly active = signal(false);
  protected readonly busy = signal(true);
  protected readonly publicKey = signal('');
  protected readonly permission = signal<NotificationPermission>('default');
  protected readonly message = signal('Verificando notificações…');
  protected readonly loadFailed = signal(false);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    if (!window.isSecureContext || !('Notification' in window) || !('PushManager' in window)
        || !('serviceWorker' in navigator)) {
      this.message.set('As notificações estão indisponíveis neste navegador.');
      this.busy.set(false);
      return;
    }
    this.busy.set(true);
    this.loadFailed.set(false);
    this.permission.set(Notification.permission);
    try {
      const worker = await registration();
      const subscription = await worker?.pushManager.getSubscription();
      const settings = await firstValueFrom(this.http.get<Settings>(URL, {
        headers: subscription ? { 'X-Push-Endpoint': subscription.endpoint } : {},
      }));
      this.active.set(settings.active);
      this.publicKey.set(settings.publicKey);
      this.showStatus();
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.message.set('As notificações estão indisponíveis neste ambiente.');
      } else {
        this.handleError(error, 'Não foi possível verificar as notificações.');
        this.loadFailed.set(true);
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async enable(): Promise<void> {
    if (this.busy() || !this.publicKey()) return;
    this.busy.set(true);
    try {
      this.message.set('Aguardando a permissão do navegador…');
      // Start the permission request inside the button's user activation.
      this.permission.set(await Notification.requestPermission());
      if (this.permission() !== 'granted') {
        this.showStatus();
        return;
      }
      this.message.set('Ativando notificações neste navegador…');
      const registered = await navigator.serviceWorker.register('/web-push-sw.js');
      const worker = registered.active ? registered : await navigator.serviceWorker.ready;
      const existing = await worker.pushManager.getSubscription();
      const subscription = existing ?? await worker.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: this.publicKey(),
      });
      try {
        await firstValueFrom(putWithCsrf(this.http, URL, { endpoint: subscription.endpoint, enabled: true }));
      } catch (error) {
        if (!existing) await subscription.unsubscribe().catch(() => false);
        throw error;
      }
      this.active.set(true);
      this.showStatus();
    } catch (error) {
      this.handleError(error, 'Não foi possível ativar as notificações. Tente novamente.');
    } finally {
      this.busy.set(false);
    }
  }

  protected async disable(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    try {
      const worker = await registration();
      const subscription = await worker?.pushManager.getSubscription();
      if (subscription) {
        await firstValueFrom(putWithCsrf(this.http, URL, { endpoint: subscription.endpoint, enabled: false }));
      }
      this.active.set(false);
      this.showStatus();
      await clearBrowserPush().catch(() => undefined);
    } catch (error) {
      this.handleError(error, 'Não foi possível desativar as notificações. Tente novamente.');
    } finally {
      this.busy.set(false);
    }
  }

  private showStatus(): void {
    if (this.permission() === 'denied') {
      this.message.set('Permissão recusada. Você pode alterá-la nas configurações do navegador.');
    } else {
      this.message.set(this.active() ? 'Notificações ativas neste navegador.' : 'Notificações desativadas neste navegador.');
    }
  }

  private handleError(error: unknown, message: string): void {
    if (error instanceof HttpErrorResponse && error.status === 401) {
      this.sessionExpired.emit();
      return;
    }
    this.message.set(message);
  }
}
