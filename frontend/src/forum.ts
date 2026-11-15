import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { CATEGORY_LABELS } from './care-labels';
import { postWithCsrf, putWithCsrf } from './csrf';

const POSTS = '/api/forum/posts';
const STATES = { DRAFT: 'Rascunho', IN_REVIEW: 'Em análise', PUBLISHED: 'Publicada', REJECTED: 'Recusada', REMOVED: 'Removida' };
const WINDOW = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'America/Sao_Paulo', dateStyle: 'long', timeStyle: 'short',
});

interface Content {
  kind: 'STORY' | 'REVIEW' | 'QUESTION';
  category: keyof typeof CATEGORY_LABELS | null;
  title: string | null;
  body: string;
  product: string | null;
  rating: number | null;
}

interface Post {
  id: string;
  content: Content;
  status: keyof typeof STATES;
}

interface Page {
  posts: Post[];
  nextAvailableAt: string | null;
  comments: OwnComment[];
}

interface OwnComment {
  id: string;
  body: string;
  status: keyof typeof STATES;
  topicKind: Content['kind'];
  publicTopicId: string | null;
}

const EMPTY: Content = { kind: 'STORY', category: null, title: null, body: '', product: null, rating: null };

@Component({
  selector: 'app-forum',
  imports: [RouterLink],
  templateUrl: './forum.html',
  styleUrl: './forum.css',
})
export class Forum {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly editorHeading = viewChild<ElementRef<HTMLElement>>('editorHeading');
  private readonly postsHeading = viewChild<ElementRef<HTMLElement>>('postsHeading');
  private readonly commentsHeading = viewChild<ElementRef<HTMLElement>>('commentsHeading');
  private readonly draftButton = viewChild<ElementRef<HTMLButtonElement>>('draftButton');
  private readonly feedback = viewChild<ElementRef<HTMLElement>>('feedback');
  private editingId: string | null = null;

  readonly sessionExpired = output();
  readonly accessLost = output();
  readonly withdrawn = output();
  readonly canParticipate = input.required<boolean>();
  protected readonly labels = CATEGORY_LABELS;
  protected readonly categories = Object.keys(CATEGORY_LABELS) as (keyof typeof CATEGORY_LABELS)[];
  protected readonly states = STATES;
  protected readonly kindLabels = { STORY: 'Relato', REVIEW: 'Avaliação de produto', QUESTION: 'Pergunta' };
  protected readonly bodyLabels = { STORY: 'Seu relato', REVIEW: 'Experiência de uso', QUESTION: 'Sua pergunta' };
  protected readonly ratings = [1, 2, 3, 4, 5];
  protected readonly page = signal<Page | null>(null);
  protected readonly content = signal(EMPTY);
  protected readonly busy = signal(false);
  protected readonly notice = signal('');
  protected readonly errors = signal<string[]>([]);

  constructor() {
    this.load();
  }

  protected load(focusAfterLoad?: () => void): void {
    this.busy.set(true);
    this.http.get<Page>(POSTS).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: page => {
        this.page.set(page);
        this.busy.set(false);
        if (focusAfterLoad) {
          afterNextRender(focusAfterLoad, { injector: this.injector });
        }
      },
      error: (error: HttpErrorResponse) => this.failure(error),
    });
  }

  protected edit<K extends keyof Content>(field: K, value: Content[K]): void {
    this.content.update(current => ({ ...current, [field]: value }));
  }

  protected editDraft(post: Post): void {
    this.editingId = post.id;
    this.content.set(post.content);
    this.notice.set('');
    this.errors.set([]);
    this.editorHeading()?.nativeElement.focus();
  }

  protected reset(): void {
    this.editingId = null;
    this.content.set(EMPTY);
    this.errors.set([]);
    this.notice.set('');
  }

  protected save(submit: boolean): void {
    if (this.busy() || !this.canParticipate()) return;
    const id = this.editingId ??= crypto.randomUUID();
    const current = this.content();
    const body = {
      ...current,
      title: current.kind === 'QUESTION' ? current.title : null,
      product: current.kind === 'REVIEW' ? current.product : null,
      rating: current.kind === 'REVIEW' ? current.rating : null,
    };
    this.begin();
    const request = submit ? postWithCsrf<Post>(this.http, `${POSTS}/${id}/submit`, body)
      : putWithCsrf<Post>(this.http, `${POSTS}/${id}`, body);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: saved => {
        if (submit) {
          this.reset();
          this.notice.set('Publicação enviada para análise.');
        } else {
          this.content.set(saved.content);
          this.notice.set('Rascunho salvo.');
        }
        this.load(() => (submit ? this.postsHeading() : this.draftButton())?.nativeElement.focus());
      },
      error: (error: HttpErrorResponse) => this.failure(error),
    });
  }

  protected withdraw(post: Post): void {
    this.withdrawAt(`${POSTS}/${post.id}/withdraw`,
      'Publicação retirada. O envio continua contando na cota do dia.');
  }

  protected withdrawComment(comment: OwnComment): void {
    this.withdrawAt(`/api/forum/comments/${comment.id}/withdraw`,
      comment.topicKind === 'QUESTION' ? 'Resposta retirada.' : 'Comentário retirado.',
      () => this.commentsHeading()?.nativeElement.focus());
  }

  private withdrawAt(url: string, message: string, focus = () => this.postsHeading()?.nativeElement.focus()): void {
    if (this.busy()) return;
    this.begin();
    postWithCsrf<void>(this.http, url, null)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          this.notice.set(message);
          this.withdrawn.emit();
          this.load(focus);
        },
        error: (error: HttpErrorResponse) => this.failure(error),
      });
  }

  protected formatWindow(value: string): string {
    return WINDOW.format(new Date(value));
  }

  private begin(): void {
    this.busy.set(true);
    this.errors.set([]);
    this.notice.set('');
  }

  private failure(error: HttpErrorResponse): void {
    this.busy.set(false);
    if (error.status === 401) {
      this.sessionExpired.emit();
      return;
    }
    const next = error.status === 429 ? error.error?.nextAvailableAt : null;
    if (error.status === 403 && error.error?.code === 'SUBSCRIPTION_REQUIRED') {
      this.accessLost.emit();
      this.errors.set(['Sua assinatura não está ativa. O texto foi mantido nesta tela.']);
    } else if (typeof next === 'string') {
      this.page.update(page => page && { ...page, nextAvailableAt: next });
      this.errors.set([`Você já enviou uma publicação hoje. Próximo envio em ${this.formatWindow(next)}, no horário de Brasília.`]);
    } else {
      const fields: Record<string, string> | undefined = error.error?.errors;
      this.errors.set(fields ? Object.values(fields) : ['Não foi possível continuar. Tente novamente.']);
    }
    afterNextRender(() => this.feedback()?.nativeElement.focus(), { injector: this.injector });
  }
}
