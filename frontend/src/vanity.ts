import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, computed, DestroyRef, ElementRef, inject, Injector, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { deleteWithCsrf, postWithCsrf, putWithCsrf } from './csrf';

const ITEMS_URL = '/api/makeup-items';
const LABELS = { OWNED: 'Tenho', RUNNING_LOW: 'Está acabando', TO_BUY: 'Comprar' } as const;
type Status = keyof typeof LABELS;
type Fields = { name: string; category: string | null; status: Status };
type Item = Fields & { id: string };
type Draft = Item & { creating: boolean };
type Errors = Partial<Record<keyof Fields | 'body', string>>;

@Component({
  selector: 'app-vanity',
  templateUrl: './vanity.html',
  styleUrl: './vanity.css',
})
export class Vanity {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly labels = LABELS;
  protected readonly statuses = Object.keys(LABELS) as Status[];
  protected readonly items = signal<Item[]>([]);
  protected readonly missingOnly = signal(false);
  protected readonly visibleItems = computed(() => this.items()
    .filter(item => !this.missingOnly() || item.status !== 'OWNED')
    .sort((a, b) => a.name.localeCompare(b.name, 'pt-BR')));
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly pending = signal(false);
  protected readonly draft = signal<Draft | null>(null);
  protected readonly removing = signal<Item | null>(null);
  protected readonly errors = signal<Errors>({});
  protected readonly error = signal('');
  protected readonly sessionExpired = signal(false);
  protected readonly notice = signal('');

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.clearMessages();
    this.http.get<Item[]>(ITEMS_URL).pipe(takeUntilDestroyed(this.destroyRef),
      finalize(() => this.loading.set(false))).subscribe({
      next: items => this.items.set(items),
      error: (response: HttpErrorResponse) => {
        this.loadFailed.set(true);
        this.failure(response, 'Não foi possível carregar seus itens. Tente novamente.');
      },
    });
  }

  protected filter(missingOnly: boolean): void {
    this.missingOnly.set(missingOnly);
    this.clearMessages();
  }

  protected openEditor(item?: Item): void {
    this.clearMessages();
    this.draft.set(item ? { ...item, creating: false } : {
      id: crypto.randomUUID(), name: '', category: null, status: this.missingOnly() ? 'TO_BUY' : 'OWNED', creating: true,
    });
    this.focus('#vanity-name');
  }

  protected update(changes: Partial<Fields>): void {
    this.draft.update(draft => draft && { ...draft, ...changes });
    this.errors.update(errors => ({ ...errors, ...Object.fromEntries(Object.keys(changes).map(key => [key, undefined])) }));
  }

  protected updateStatus(status: string): void {
    this.update({ status: status as Status });
  }

  protected cancelEdit(draft: Draft): void {
    this.draft.set(null);
    this.clearMessages();
    this.focus(draft.creating ? '#vanity-add' : '#vanity-edit-' + draft.id);
  }

  protected save(draft: Draft): void {
    if (this.pending() || this.sessionExpired()) return;
    this.clearMessages();
    const fields: Fields = { name: draft.name.trim(), category: draft.category?.trim() || null, status: draft.status };
    if (!fields.name) {
      this.errors.set({ name: 'Informe o nome do item.' });
      this.focus('#vanity-name');
      return;
    }
    const request = draft.creating
      ? postWithCsrf<Item>(this.http, ITEMS_URL, { id: draft.id, ...fields })
      : putWithCsrf<Item>(this.http, ITEMS_URL + '/' + draft.id, fields);
    this.run(request, saved => {
      this.store(saved);
      this.draft.set(null);
      this.notice.set(draft.creating ? 'Item adicionado.' : 'Alterações salvas.');
      this.focusItem(saved.id);
    }, 'Não foi possível salvar o item. Confira os dados e tente novamente.');
  }

  protected purchase(item: Item): void {
    this.run(postWithCsrf<Item>(this.http, ITEMS_URL + '/' + item.id + '/purchased', null), saved => {
      this.store(saved);
      this.notice.set(this.missingOnly()
        ? saved.name + ' foi marcado como comprado e saiu da lista O que falta.'
        : saved.name + ' foi marcado como comprado.');
      this.focus('#vanity-notice');
    }, 'Não foi possível marcar o item como comprado. Tente novamente.');
  }

  protected confirmRemoval(item: Item): void {
    this.clearMessages();
    this.removing.set(item);
    this.focus('#vanity-keep');
  }

  protected cancelRemoval(item: Item): void {
    this.clearMessages();
    this.removing.set(null);
    this.focus('#vanity-remove-' + item.id);
  }

  protected remove(item: Item): void {
    const nextIndex = this.visibleItems().findIndex(current => current.id === item.id);
    this.run(deleteWithCsrf<void>(this.http, ITEMS_URL + '/' + item.id), () => {
      this.items.update(items => items.filter(current => current.id !== item.id));
      this.removing.set(null);
      this.notice.set(item.name + ' foi removido.');
      this.focusItem(this.visibleItems()[nextIndex]?.id ?? this.visibleItems().at(-1)?.id);
    }, 'Não foi possível remover o item. Tente novamente.');
  }

  private run<T>(request: Observable<T>, success: (value: T) => void, error: string): void {
    if (this.pending()) return;
    this.clearMessages();
    this.pending.set(true);
    request.pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.pending.set(false))).subscribe({
      next: success,
      error: (response: HttpErrorResponse) => this.failure(response, error),
    });
  }

  private store(item: Item): void {
    this.items.update(items => [...items.filter(current => current.id !== item.id), item]);
  }

  private clearMessages(): void {
    this.error.set('');
    this.errors.set({});
    this.notice.set('');
  }

  private failure(response: HttpErrorResponse, message: string): void {
    // A write after session expiry can return 403 if CSRF was fetched for an anonymous session.
    if (response.status === 401 || response.status === 403) {
      this.sessionExpired.set(true);
      this.items.set([]);
      this.error.set(this.draft()
        ? 'Não foi possível confirmar sua sessão. Copie os dados do item antes de reabrir a Penteadeira.'
        : 'Não foi possível confirmar sua sessão. Reabra a Penteadeira para continuar.');
      this.focus('#vanity-error');
      return;
    }
    const errors = response.error?.errors as Errors | undefined;
    if (this.draft() && errors) {
      this.errors.set(errors);
      const field = (['name', 'category', 'status'] as const).find(key => errors[key]);
      if (field) {
        this.focus('#vanity-' + field);
        return;
      }
    }
    this.error.set(errors?.body ?? message);
    this.focus('#vanity-error');
  }

  private focusItem(id?: string): void {
    this.focus(id ? '#vanity-item-' + id : '#vanity-add', this.missingOnly() ? '#vanity-missing' : '#vanity-add');
  }

  private focus(selector: string, fallback?: string): void {
    afterNextRender(() => {
      const root = this.host.nativeElement;
      (root.querySelector<HTMLElement>(selector) ?? (fallback ? root.querySelector<HTMLElement>(fallback) : null))?.focus();
    }, { injector: this.injector });
  }
}
