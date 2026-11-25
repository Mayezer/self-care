import { Component, input } from '@angular/core';

export interface HairRoutineData {
  modelId: string;
  version: number;
  reviewedOn: string;
  personalized: boolean;
  summary: string;
  steps: { id: string; title: string; instruction: string }[];
  notes: string[];
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
      @if (routine().notes.length) {
        <ul class="routine-notes">
          @for (note of routine().notes; track note) { <li>{{ note }}</li> }
        </ul>
      }
      <p class="hint">Hidratação, nutrição e reconstrução continuam disponíveis para organizar cuidados escolhidos por você.
        O perfil sozinho não define intervalos seguros para esses tratamentos.</p>
      <div class="routine-sources">
        <span>Orientações de dermatologistas:</span>
        <a href="https://www.aad.org/public/everyday-care/hair-scalp-care/hair/healthy-hair-tips" target="_blank" rel="noopener noreferrer">Cuidados com os fios</a>
        <a href="https://www.aad.org/public/everyday-care/hair-scalp-care/hair/curly-hair-care" target="_blank" rel="noopener noreferrer">Cachos e crespos</a>
      </div>
    } @else {
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
}
