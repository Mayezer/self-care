import { Component, input } from '@angular/core';

@Component({
  selector: 'app-skin-routine-steps',
  template: `
    @if (steps()?.length) {
      <details class="skin-routine-steps">
        <summary>Ver passos da rotina<span class="visually-hidden">: {{ title() }}</span></summary>
        <ol>
          @for (step of steps(); track $index) { <li>{{ step }}</li> }
        </ol>
      </details>
    }
  `,
})
export class SkinRoutineSteps {
  readonly steps = input.required<readonly string[] | null>();
  readonly title = input.required<string>();
}
