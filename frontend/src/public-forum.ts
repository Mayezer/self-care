import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { NgTemplateOutlet } from '@angular/common';
import { afterNextRender, Component, DestroyRef, ElementRef, HostListener, inject, Injector, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import { catchError, distinctUntilChanged, filter, map, of, startWith, Subject, switchMap } from 'rxjs';
import { PageHeading } from './page-heading';
import { CATEGORY_LABELS } from './care-labels';
import { Forum } from './forum';
import { ForumReport } from './forum-report';
import { postWithCsrf } from './csrf';

const TOPICS = '/api/forum/public/topics';
const PARTICIPATION = '/api/forum/participation';
const KIND_LABELS = { STORY: 'Relato', REVIEW: 'Avaliação de produto', QUESTION: 'Pergunta' };

interface Topic {
  id: string;
  kind: keyof typeof KIND_LABELS;
  category: keyof typeof CATEGORY_LABELS;
  title: string | null;
  body: string;
  product: string | null;
  rating: number | null;
}

interface TopicPage {
  topics: Topic[];
  hasNext: boolean;
}

interface TopicDetail {
  topic: Topic;
  comments: { id: string; body: string }[];
  images: { id: string; altText: string }[];
}

type Result = { kind: 'loading' | 'missing' | 'error' } |
  { kind: 'list'; page: TopicPage } | { kind: 'detail'; detail: TopicDetail };
type Participation = 'VISITOR' | 'INACTIVE' | 'ACTIVE' | 'UNAVAILABLE' | 'ERROR';

@Component({
  selector: 'app-public-forum',
  imports: [PageHeading, RouterLink, NgTemplateOutlet, Forum, ForumReport],
  templateUrl: './public-forum.html',
  styleUrl: './public-forum.css',
})
export class PublicForum {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly retry = new Subject<boolean>();
  private readonly checkAccess = new Subject<void>();
  private readonly resultsHeading = viewChild<ElementRef<HTMLElement>>('resultsHeading');
  private readonly commentFeedback = viewChild<ElementRef<HTMLElement>>('commentFeedback');
  private focusResults = false;
  private commentRequestId: string | null = null;

  protected readonly labels = CATEGORY_LABELS;
  protected readonly kinds = KIND_LABELS;
  protected readonly result = signal<Result>({ kind: 'loading' });
  protected readonly topicId = signal<string | null>(null);
  protected readonly query = signal('');
  protected readonly questionsOnly = signal(false);
  protected readonly page = signal(1);
  protected readonly participation = signal<Participation | null>(null);
  protected readonly canModerate = signal(false);
  protected readonly comment = signal('');
  protected readonly sending = signal(false);
  protected readonly commentError = signal('');
  protected readonly commentNotice = signal('');

  constructor() {
    this.checkAccess.pipe(
      switchMap(() => this.http.get<{ state: Participation; canModerate: boolean }>(PARTICIPATION).pipe(
        catchError((error: HttpErrorResponse) => of({
          state: error.status === 404 ? 'UNAVAILABLE' as const : 'ERROR' as const, canModerate: false,
        })),
      )),
      takeUntilDestroyed(),
    ).subscribe(access => {
      this.participation.set(access.state);
      this.canModerate.set(access.canModerate);
    });

    this.router.events.pipe(
      filter(event => event instanceof NavigationEnd),
      startWith(null),
      map(() => this.route.snapshot),
      distinctUntilChanged(),
      switchMap(route => this.retry.pipe(
        startWith(true),
        switchMap(showLoading => {
          const id = route.paramMap.get('topicId');
          const q = (route.queryParamMap.get('q') ?? '').trim().slice(0, 120);
          const n = Number(route.queryParamMap.get('pagina') ?? 1);
          const page = Number.isInteger(n) && n >= 1 && n <= 2_147_483_647 ? n : 1;
          const questionsOnly = route.queryParamMap.get('tipo') === 'perguntas';
          if (id !== this.topicId()) {
            this.comment.set('');
            this.commentNotice.set('');
            this.commentError.set('');
            this.commentRequestId = null;
            this.sending.set(false);
            this.topicId.set(id);
          }
          this.query.set(q);
          this.questionsOnly.set(questionsOnly);
          this.page.set(page);
          const params: Record<string, string | number> = { q, page };
          if (questionsOnly) params['kind'] = 'QUESTION';
          const request = id ? this.http.get<TopicDetail>(TOPICS + '/' + encodeURIComponent(id)).pipe(
            map((detail): Result => ({ kind: 'detail', detail })),
          ) : this.http.get<TopicPage>(TOPICS, { params }).pipe(
            map((page): Result => ({ kind: 'list', page })),
          );
          const result = request.pipe(
            catchError((error: HttpErrorResponse): Result[] => [
              { kind: error.status === 404 || id && error.status === 400 ? 'missing' : 'error' },
            ]),
          );
          return showLoading ? result.pipe(startWith({ kind: 'loading' } as Result)) : result;
        }),
      )),
      takeUntilDestroyed(),
    ).subscribe(result => {
      this.result.set(result);
      if (result.kind === 'loading') return;
      if (result.kind === 'list' || result.kind === 'detail') this.refreshParticipation();
      if (this.focusResults) {
        this.focusResults = false;
        afterNextRender(() => this.resultsHeading()?.nativeElement.focus(), { injector: this.injector });
      }
    });
  }

  protected search(value: string, questionsOnly: boolean): void {
    const q = value.trim();
    this.focusResults = true;
    if (q === this.query() && this.questionsOnly() === questionsOnly && this.page() === 1) this.reload();
    else void this.router.navigate(['/forum'], { queryParams: { q: q || null, tipo: questionsOnly ? 'perguntas' : null } });
  }

  protected changePage(page: number): void {
    this.focusResults = true;
    void this.router.navigate(['/forum'], {
      queryParams: { q: this.query() || null, tipo: this.questionsOnly() ? 'perguntas' : null, pagina: page > 1 ? page : null },
    });
  }

  protected reload(showLoading = true): void {
    this.retry.next(showLoading);
  }

  @HostListener('window:pageshow', ['$event'])
  protected refreshRestoredPage(event: PageTransitionEvent): void {
    if (event.persisted) this.reload();
  }

  protected refreshParticipation(): void {
    this.checkAccess.next();
  }

  protected accessLost(state: 'VISITOR' | 'INACTIVE'): void {
    this.participation.set(state);
    this.refreshParticipation();
  }

  protected sendComment(topic: Topic): void {
    if (this.participation() !== 'ACTIVE' || this.sending()) return;
    const question = topic.kind === 'QUESTION';
    const requestId = this.commentRequestId ??= crypto.randomUUID();
    this.sending.set(true);
    this.commentError.set('');
    this.commentNotice.set('');
    postWithCsrf(this.http, `/api/forum/topics/${topic.id}/comments/${requestId}`, { body: this.comment() })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          if (this.commentRequestId !== requestId) return;
          this.commentRequestId = null;
          this.comment.set('');
          this.sending.set(false);
          this.commentNotice.set(question ? 'Resposta enviada para análise.' : 'Comentário enviado para análise.');
          this.focusCommentFeedback();
        },
        error: (error: HttpErrorResponse) => {
          if (this.commentRequestId !== requestId) return;
          this.sending.set(false);
          if (error.status === 403 && error.error?.code === 'SUBSCRIPTION_REQUIRED') {
            this.accessLost('INACTIVE');
            this.commentError.set(question
              ? 'Sua assinatura não está ativa. A resposta foi mantida nesta tela.'
              : 'Sua assinatura não está ativa. O comentário foi mantido nesta tela.');
          } else if (error.status === 401) {
            this.accessLost('VISITOR');
            this.commentError.set('Sua sessão terminou. Entre novamente para participar.');
          } else if (error.status === 429) {
            this.commentError.set('Você enviou vários comentários. Aguarde um minuto para tentar novamente.');
          } else if (error.status === 404) {
            this.commentError.set('O tópico não está disponível para receber comentários.');
          } else {
            this.commentError.set(error.error?.errors?.body ?? 'Não foi possível enviar o comentário. Tente novamente.');
          }
          this.focusCommentFeedback();
        },
      });
  }

  private focusCommentFeedback(): void {
    afterNextRender(() => this.commentFeedback()?.nativeElement.focus(), { injector: this.injector });
  }

  protected title(topic: Topic): string {
    return topic.title ?? topic.product ?? this.kinds[topic.kind] + ' · ' + this.labels[topic.category];
  }

  protected excerpt(body: string): string {
    return body.length <= 160 ? body : body.slice(0, 159).trimEnd() + '…';
  }

  protected imageUrl(topicId: string, imageId: string): string {
    return TOPICS + '/' + topicId + '/images/' + imageId;
  }
}
