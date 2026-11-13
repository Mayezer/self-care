import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { deleteWithCsrf, putWithCsrf } from './csrf';

const photosUrl = '/api/hair-diary/photos';
const dateFormat = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long', timeZone: 'UTC' });

interface Photo {
  id: string;
  date: string;
}

@Component({
  selector: 'app-hair-diary',
  imports: [RouterLink],
  templateUrl: './hair-diary.html',
  styleUrl: './hair-diary.css',
})
export class HairDiary {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly timeline = viewChild<ElementRef<HTMLHeadingElement>>('timeline');
  private attempt?: { file: File; date: string; id: string };

  readonly today = input.required<string>();
  readonly sessionExpired = output();
  protected readonly photos = signal<Photo[] | null>(null);
  protected readonly loading = signal(true);
  protected readonly locked = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');

  constructor() {
    this.load();
  }

  protected load(focusTimeline = false): void {
    this.loading.set(true);
    this.error.set('');
    this.http.get<Photo[]>(photosUrl).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: photos => {
        this.photos.set(photos);
        this.loading.set(false);
        if (focusTimeline) {
          afterNextRender(() => this.timeline()?.nativeElement.focus(), { injector: this.injector });
        }
      },
      error: (error: HttpErrorResponse) => this.handle(error, 'Não foi possível carregar as fotos.'),
    });
  }

  protected photoUrl(id: string): string {
    return photosUrl + '/' + encodeURIComponent(id);
  }

  protected date(value: string): string {
    return dateFormat.format(new Date(value + 'T00:00:00Z'));
  }

  protected save(date: string, field: HTMLInputElement): void {
    const file = field.files?.[0];
    if (!file || this.saving()) return;
    this.notice.set('');
    if (file.size > 5 * 1024 * 1024) {
      this.error.set('Escolha uma foto de até 5 MiB.');
      return;
    }
    if (this.attempt?.file !== file || this.attempt.date !== date) {
      this.attempt = { file, date, id: crypto.randomUUID() };
    }
    const form = new FormData();
    form.set('date', date);
    form.set('photo', file, 'photo');
    this.saving.set(true);
    this.error.set('');
    putWithCsrf<void>(this.http, this.photoUrl(this.attempt.id), form).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        field.value = '';
        this.attempt = undefined;
        this.saving.set(false);
        this.notice.set('Foto adicionada ao diário.');
        this.load(true);
      },
      error: (error: HttpErrorResponse) => this.handle(error, error.status === 413
        ? 'Escolha uma foto de até 5 MiB.' : 'Não foi possível guardar a foto.'),
    });
  }

  protected remove(id: string): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.notice.set('');
    deleteWithCsrf<void>(this.http, this.photoUrl(id)).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.saving.set(false);
        this.notice.set('Foto excluída.');
        this.photos.update(photos => photos?.filter(photo => photo.id !== id) ?? null);
        this.load(true);
      },
      error: (error: HttpErrorResponse) => this.handle(error, 'Não foi possível excluir a foto.'),
    });
  }

  private handle(error: HttpErrorResponse, fallback: string): void {
    this.loading.set(false);
    this.saving.set(false);
    if (error.status === 401 || error.status === 403) {
      this.photos.set(null);
      this.attempt = undefined;
      this.notice.set('');
      if (error.status === 401) this.sessionExpired.emit();
      else this.locked.set(true);
      return;
    }
    this.error.set(error.status === 400 && typeof error.error?.message === 'string' ? error.error.message : fallback);
  }
}
