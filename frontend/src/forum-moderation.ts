import { NgTemplateOutlet } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, HostListener, inject, Injector, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { postWithCsrf } from './csrf';
import { CATEGORY_LABELS } from './care-labels';
import { ForumTargetKind } from './forum-report';
import { PageHeading } from './page-heading';

const MODERATION = '/api/forum/moderation';
const ACTION_MESSAGES = {
  APPROVE: 'Conteúdo aprovado.',
  REJECT: 'Conteúdo recusado.',
  REMOVE: 'Conteúdo removido.',
};
type Decision = keyof typeof ACTION_MESSAGES;

interface Target {
  kind: ForumTargetKind;
  id: string;
  topicId: string;
  title: string | null;
  product: string | null;
  rating: number | null;
  category: keyof typeof CATEGORY_LABELS;
  preview: string;
  status: 'IN_REVIEW' | 'PUBLISHED' | 'REJECTED' | 'REMOVED';
  publicTopic: boolean;
}

interface Queue {
  pending: Target[];
  reports: { id: string; reason: string; target: Target }[];
}

@Component({
  selector: 'app-forum-moderation',
  imports: [PageHeading, RouterLink, NgTemplateOutlet],
  templateUrl: './forum-moderation.html',
  styleUrls: ['./public-forum.css', './forum.css'],
})
export class ForumModeration {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly feedback = viewChild<ElementRef<HTMLElement>>('feedback');
  protected readonly queue = signal<Queue | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly message = signal('');
  private readonly labels = { TOPIC: 'Tópico', COMMENT: 'Resposta ou comentário', IMAGE: 'Imagem' };

  constructor() { this.load(); }

  @HostListener('window:pageshow', ['$event'])
  protected refreshRestoredPage(event: PageTransitionEvent): void {
    if (event.persisted) {
      this.queue.set(null);
      this.load();
    }
  }

  protected load(): void {
    this.busy.set(true);
    this.error.set('');
    this.http.get<Queue>(MODERATION + '/queue').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: queue => {
        this.queue.set(queue);
        this.busy.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.queue.set(null);
        this.fail(error);
      },
    });
  }

  protected decide(target: Target, decision: Decision): void {
    this.act(`/topics/${target.topicId}/${target.kind}/${target.id}/${decision}`, ACTION_MESSAGES[decision]);
  }

  protected closeReport(id: string): void {
    this.act(`/reports/${id}/close`, 'Denúncia encerrada.');
  }

  protected imageUrl(target: Target): string {
    return `${MODERATION}/topics/${target.topicId}/images/${target.id}`;
  }

  protected label(target: Target): string {
    return this.labels[target.kind] + ' · ' + CATEGORY_LABELS[target.category];
  }

  private act(path: string, message: string): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    postWithCsrf<void>(this.http, MODERATION + path, null).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.message.set(message);
        this.load();
        this.focusFeedback();
      },
      error: (error: HttpErrorResponse) => this.fail(error),
    });
  }

  private fail(error: HttpErrorResponse): void {
    this.busy.set(false);
    if (error.status === 401 || error.status === 403) this.queue.set(null);
    this.error.set(error.status === 401 ? 'Entre com uma conta de moderação.'
      : error.status === 403 ? 'Sua conta não tem acesso à moderação.'
      : error.status === 409 ? 'O estado do conteúdo mudou. Atualize a fila.'
      : 'Não foi possível consultar ou atualizar a fila. Tente novamente.');
    this.focusFeedback();
  }

  private focusFeedback(): void {
    afterNextRender(() => this.feedback()?.nativeElement.focus(), { injector: this.injector });
  }
}
