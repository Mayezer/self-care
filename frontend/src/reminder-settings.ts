import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { CATEGORY_LABELS } from './care-labels';
import { putWithCsrf } from './csrf';
import { WebPushSettings } from './web-push-settings';

const URL = '/api/agenda/reminder-preferences';
type Category = keyof typeof CATEGORY_LABELS;
type Field = 'enabled' | 'categories' | 'time';
type Errors = Partial<Record<Field | 'body', string>>;

interface Preferences {
  enabled: boolean;
  categories: Category[];
  time: string | null;
}

type Settings = Preferences;

@Component({
  selector: 'app-reminder-settings',
  imports: [RouterLink, WebPushSettings],
  templateUrl: './reminder-settings.html',
  styleUrl: './reminder-settings.css',
})
export class ReminderSettings {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly form = viewChild<ElementRef<HTMLFormElement>>('form');

  readonly sessionExpired = output();
  protected readonly settings = signal<Settings | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal('');
  protected readonly saving = signal(false);
  protected readonly errors = signal<Errors>({});
  protected readonly notice = signal('');
  protected readonly labels = CATEGORY_LABELS;
  protected readonly categories = Object.keys(CATEGORY_LABELS) as Category[];

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set('');
    this.http.get<Settings>(URL).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: settings => {
        this.settings.set(settings);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        if (error.status === 401) {
          this.sessionExpired.emit();
          return;
        }
        this.loading.set(false);
        this.loadError.set('Não foi possível carregar as preferências. Tente novamente.');
      },
    });
  }

  protected edit<K extends Field>(field: K, value: Settings[K]): void {
    this.settings.update(current => current && { ...current, [field]: value });
    this.errors.update(errors => ({ ...errors, [field]: undefined, body: undefined }));
    this.notice.set('');
  }

  protected toggleCategory(category: Category, checked: boolean): void {
    const current = this.settings();
    if (!current) return;
    this.edit('categories', checked ? [...current.categories, category] : current.categories.filter(item => item !== category));
  }

  protected save(): void {
    const settings = this.settings();
    if (!settings || this.saving()) return;
    const { enabled, categories, time } = settings;
    this.notice.set('');
    const errors: Errors = {};
    if (enabled && !categories.length) errors.categories = 'Escolha pelo menos uma categoria.';
    if (enabled && !time) errors.time = 'Informe um horário para os lembretes.';
    if (Object.keys(errors).length) {
      this.showErrors(errors);
      return;
    }
    this.errors.set({});
    this.saving.set(true);
    putWithCsrf<Settings>(this.http, URL, { enabled, categories, time })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: saved => {
          this.settings.set(saved);
          this.saving.set(false);
          this.notice.set(saved.enabled ? 'Preferências salvas. Lembretes ativos na agenda.'
            : 'Preferências salvas. Lembretes desativados na agenda.');
        },
        error: (error: HttpErrorResponse) => {
          this.saving.set(false);
          if (error.status === 401) {
            this.sessionExpired.emit();
            return;
          }
          this.showErrors(error.status === 400 && error.error?.errors
            ? error.error.errors : { body: 'Não foi possível salvar. Suas escolhas foram mantidas. Tente novamente.' });
        },
      });
  }

  private showErrors(errors: Errors): void {
    this.errors.set(errors);
    afterNextRender(() => {
      this.form()?.nativeElement.querySelector<HTMLElement>(
        errors.body ? '[data-save-error]' : 'input[aria-invalid="true"], select[aria-invalid="true"]',
      )?.focus();
    }, { injector: this.injector });
  }
}
