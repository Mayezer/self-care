import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, input, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { postWithCsrf } from './csrf';

export type ForumTargetKind = 'TOPIC' | 'COMMENT' | 'IMAGE';

@Component({
  selector: 'app-forum-report',
  styles: [`
    :host { display: block; }
    summary { cursor: pointer; width: fit-content; }
    fieldset { display: grid; gap: var(--space-sm); min-width: 0; margin-block: var(--space-sm); }
    button { justify-self: start; }
  `],
  template: `
    <details>
      <summary>{{ action() }}</summary>
      @if (!sent()) {
        <form (submit)="$event.preventDefault(); send()">
          <fieldset [disabled]="busy()">
            <div class="field">
              <label [for]="'reason-' + targetId()">Motivo da denúncia</label>
              <textarea [id]="'reason-' + targetId()" #reasonInput [value]="reason()"
                        (input)="reason.set(reasonInput.value)" rows="3" maxlength="500" required></textarea>
            </div>
            <button type="submit" class="secondary">Enviar denúncia</button>
          </fieldset>
        </form>
      }
      @if (sent()) {
        <p #feedback tabindex="-1" role="status">Denúncia encaminhada à moderação.</p>
      } @else if (error()) {
        <p #feedback tabindex="-1" role="alert" class="field-error">{{ error() }}</p>
      }
    </details>
  `,
})
export class ForumReport {
  readonly topicId = input.required<string>();
  readonly kind = input.required<ForumTargetKind>();
  readonly targetId = input.required<string>();
  readonly action = input.required<string>();

  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly feedback = viewChild<ElementRef<HTMLElement>>('feedback');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal('');

  protected send(): void {
    if (this.busy() || this.sent()) return;
    this.busy.set(true);
    this.error.set('');
    const path = `/api/forum/topics/${this.topicId()}/reports/${this.kind()}/${this.targetId()}`;
    postWithCsrf<void>(this.http, path, { reason: this.reason() }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.sent.set(true);
        this.reason.set('');
        this.focusFeedback();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(error.status === 401 ? 'Entre novamente para denunciar.'
          : error.status === 404 ? 'Este conteúdo não está mais disponível.'
          : error.error?.errors?.reason ?? 'Não foi possível enviar a denúncia. Tente novamente.');
        this.focusFeedback();
      },
    });
  }

  private focusFeedback(): void {
    afterNextRender(() => this.feedback()?.nativeElement.focus(), { injector: this.injector });
  }
}
