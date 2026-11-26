import { Component, computed, input } from '@angular/core';
import { formatHairDate, type Stage } from './hair-preview-draft';

export interface HairScheduleData {
  sourceRevision: string;
  notes: string[];
  sessions: { occurrenceId?: number; date: string; stage: Stage; reason: string }[];
  intervals?: { stage: Stage; every: number; unit: 'DAYS' | 'WEEKS' | 'MONTHS'; lastApplication: string | null; informed: boolean }[];
}

export interface HairRoutineData {
  modelId: string;
  version: number;
  reviewedOn: string;
  personalized: boolean;
  summary: string;
  steps: { id: string; title: string; instruction: string }[];
  notes: string[];
  schedule?: HairScheduleData | null;
}

@Component({
  selector: 'app-hair-routine',
  styles: `
    :host { display: grid; gap: var(--space-sm); }
    h3 { font-size: 1rem; }
    ol, ul { display: grid; gap: var(--space-sm); padding-inline-start: 1.25rem; }
    li { padding-inline-start: .25rem; }
    li p { margin-block-start: .25rem; color: var(--muted); font-size: .875rem; }
    .routine-notes { color: var(--muted); font-size: .875rem; }
    .routine-sources { display: flex; flex-wrap: wrap; gap: .35rem .75rem; font-size: .8125rem; }
  `,
  template: `
    @if (view() === 'summary') {
      <h3>{{ routine().personalized ? 'Sua rotina de cuidado' : 'Rotina geral de cuidado' }}</h3>
      <p>{{ routine().summary }}</p>
      @if (!routine().schedule && routine().notes.length) {
        <ul class="routine-notes">
          @for (note of routine().notes; track note) { <li>{{ note }}</li> }
        </ul>
      }
      @if (routine().schedule; as schedule) {
        <p class="hint">Confira os cuidados e seus motivos em cada data. Os intervalos de nutrição e reconstrução são os que você informou;
          o app não diagnostica falta de óleo ou queratina.</p>
        <details>
          <summary>Critérios e informações que faltam</summary>
          <ul class="routine-notes">
            @for (note of routine().notes; track note) { <li>{{ note }}</li> }
            @for (note of schedule.notes; track note) { <li>{{ note }}</li> }
          </ul>
        </details>
      } @else {
        <p class="hint">Hidratação, nutrição e reconstrução continuam disponíveis para organizar cuidados escolhidos por você.
          O perfil sozinho não define intervalos seguros para esses tratamentos.</p>
      }
      <div class="routine-sources">
        <span>Orientações de dermatologistas:</span>
        <a href="https://www.aad.org/public/everyday-care/hair-scalp-care/hair/healthy-hair-tips" target="_blank" rel="noopener noreferrer">Cuidados com os fios</a>
        <a href="https://www.aad.org/public/everyday-care/hair-scalp-care/hair/curly-hair-care" target="_blank" rel="noopener noreferrer">Cachos e crespos</a>
        @if (routine().schedule) {
          <a href="https://www.sbd.org.br/alimente-seus-fios/" target="_blank" rel="noopener noreferrer">Hidratação: orientação geral da SBD</a>
        }
      </div>
    } @else {
      @if (session(); as original) {
        <p class="hint">{{ stage() && stage() !== original.stage
          ? 'Etapa alterada por você. Confira a orientação e o intervalo antes de aplicar.' : original.reason }}</p>
        @if (date() !== original.date) {
          <p class="hint">Sugestão original para {{ dateLabel(original.date) }}. Esta sessão foi reagendada;
            confira os intervalos na nova data antes de aplicar o cuidado.</p>
        }
      }
      @if (extraCare(); as instruction) { <p><strong>Cuidado extra desta sessão:</strong> {{ instruction }}</p> }
      <ol>
        @for (step of routine().steps; track step.id) {
          <li><strong>{{ step.title }}</strong><p>{{ step.instruction }}</p></li>
        }
      </ol>
    }
  `,
})
export class HairRoutine {
  readonly routine = input.required<HairRoutineData>();
  readonly view = input<'summary' | 'steps'>('steps');
  readonly date = input<string>();
  readonly stage = input<Stage>();
  readonly occurrenceId = input<number>();
  protected readonly dateLabel = formatHairDate;
  protected readonly session = computed(() => this.routine().schedule?.sessions.find(item =>
    this.occurrenceId() !== undefined ? item.occurrenceId === this.occurrenceId() : item.date === this.date()));
  protected readonly extraCare = computed(() => {
    switch (this.stage() ?? this.session()?.stage) {
      case 'HYDRATION': return 'Hidratação. Siga a orientação do cuidado que você conhece; não acumule máscaras para compensar uma sessão adiada.';
      case 'NUTRITION': return 'Nutrição. Use o cuidado emoliente já orientado e respeite a forma de aplicação. Esta etapa não pede óleo puro ou aquecido.';
      case 'RECONSTRUCTION': return 'Reconstrução. Siga a orientação do tratamento proteico conhecido, incluindo o intervalo. Não aumente a frequência para compensar uma sessão perdida.';
      default: return '';
    }
  });
}
