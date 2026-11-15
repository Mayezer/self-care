import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { RouterLink } from '@angular/router';
import { postWithCsrf, putWithCsrf } from './csrf';

const URL = '/api/makeup-cleaning';
const LABELS = { BRUSHES: 'Pincéis', SPONGES: 'Esponjas' } as const;
type Accessory = keyof typeof LABELS;
type Schedule = { accessory: Accessory; intervalDays: number; active: boolean; revision: number; nextDate: string | null };
type Settings = { today: string; defaultInterval: number; maxInterval: number; schedules: Schedule[] };
type Draft = { accessory: Accessory; revision: number; interval: string; startDate: string; resuming: boolean };
type Errors = Partial<Record<'intervalDays' | 'startDate' | 'body', string>>;

@Component({
  selector: 'app-makeup-cleaning',
  imports: [RouterLink],
  templateUrl: './makeup-cleaning.html',
  styleUrl: './makeup-cleaning.css',
})
export class MakeupCleaning {
  readonly remindersAvailable = input(false);
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly formatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeZone: 'UTC' });
  protected readonly labels = LABELS;
  protected readonly accessories = Object.keys(LABELS) as Accessory[];
  protected readonly settings = signal<Settings | null>(null);
  protected readonly draft = signal<Draft | null>(null);
  protected readonly pending = signal(false);
  protected readonly errors = signal<Errors>({});
  protected readonly notice = signal('');
  protected readonly refreshNeeded = signal(false);
  protected readonly sessionExpired = signal(false);

  constructor() {
    this.load();
  }

  focusHeading(): void {
    this.host.nativeElement.querySelector<HTMLElement>('#limpeza')?.focus();
  }

  protected load(): void {
    this.run(this.http.get<Settings>(URL), settings => {
      this.settings.set(settings);
      this.refreshNeeded.set(false);
      if (this.draft()) {
        // The draft remains editable until the person explicitly reloads the updated schedule.
        this.notice.set('Lembretes atualizados. Confira a série antes de editar novamente.');
        this.draft.set(null);
        this.focus('#cleaning-notice');
      }
    }, 'Não foi possível carregar os lembretes.');
  }

  protected schedule(accessory: Accessory): Schedule | undefined {
    return this.settings()?.schedules.find(schedule => schedule.accessory === accessory);
  }

  protected format(date: string): string {
    return this.formatter.format(new Date(date + 'T00:00:00Z'));
  }

  protected open(accessory: Accessory, settings: Settings): void {
    const current = this.schedule(accessory);
    this.clearMessages();
    this.draft.set({
      accessory, revision: current?.revision ?? 0,
      interval: String(current?.intervalDays ?? settings.defaultInterval),
      startDate: current?.nextDate ?? '',
      resuming: !!current && !current.active,
    });
    this.focus('#cleaning-date');
  }

  protected cancel(accessory: Accessory): void {
    this.clearMessages();
    this.draft.set(null);
    this.focus('#cleaning-edit-' + accessory);
  }

  protected change(value: Partial<Pick<Draft, 'interval' | 'startDate'>>): void {
    this.draft.update(draft => draft && { ...draft, ...value });
    this.errors.update(errors => ({ ...errors,
      ...(value.interval !== undefined ? { intervalDays: undefined } : { startDate: undefined }) }));
  }

  protected save(draft: Draft, settings: Settings): void {
    if (this.pending() || this.refreshNeeded() || this.sessionExpired()) return;
    const intervalDays = Number(draft.interval);
    const errors: Errors = {};
    if (!Number.isInteger(intervalDays) || intervalDays < 1 || intervalDays > settings.maxInterval) {
      errors.intervalDays = 'Informe um intervalo de 1 a ' + settings.maxInterval + ' dias.';
    }
    if (!draft.startDate || draft.startDate < settings.today) {
      errors.startDate = 'Escolha hoje ou uma data futura.';
    }
    if (Object.keys(errors).length) {
      this.notice.set('');
      this.errors.set(errors);
      this.focus(errors.startDate ? '#cleaning-date' : '#cleaning-interval');
      return;
    }
    this.run(putWithCsrf<Settings>(this.http, URL + '/' + draft.accessory, {
      revision: draft.revision, intervalDays, startDate: draft.startDate,
    }), saved => {
      this.draft.set(null);
      this.accept(saved, draft.resuming ? 'Lembrete retomado' : 'Lembrete salvo', draft.accessory);
    }, 'Não foi possível salvar o lembrete. Tente novamente.');
  }

  protected pause(schedule: Schedule): void {
    this.run(postWithCsrf<Settings>(this.http, URL + '/' + schedule.accessory + '/pause', { revision: schedule.revision }),
      saved => this.accept(saved, 'Lembrete pausado', schedule.accessory),
      'Não foi possível pausar o lembrete. Tente novamente.');
  }

  private accept(saved: Settings, message: string, accessory: Accessory): void {
    this.settings.set(saved);
    this.notice.set(message + ': ' + LABELS[accessory].toLowerCase() + '.');
    this.focus('#cleaning-notice');
  }

  private run(request: Observable<Settings>, success: (settings: Settings) => void, fallback: string): void {
    if (this.pending() || this.sessionExpired()) return;
    this.clearMessages();
    this.pending.set(true);
    request.pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.pending.set(false))).subscribe({
      next: success,
      error: (response: HttpErrorResponse) => {
        if (response.status === 401 || response.status === 403) {
          this.sessionExpired.set(true);
          this.settings.set(null);
          this.errors.set({ body: 'Sua sessão terminou. Reabra a Penteadeira para continuar.' });
          this.focus('#cleaning-error');
          return;
        }
        if (response.status === 409) this.refreshNeeded.set(true);
        const errors = response.error?.errors as Errors | undefined;
        this.errors.set(errors ?? { body: fallback });
        this.focus(errors?.startDate ? '#cleaning-date' : errors?.intervalDays ? '#cleaning-interval' : '#cleaning-error');
      },
    });
  }

  private clearMessages(): void {
    this.errors.set({});
    this.notice.set('');
  }

  private focus(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(),
      { injector: this.injector });
  }
}
