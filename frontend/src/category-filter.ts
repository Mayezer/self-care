import { Component, input, output } from '@angular/core';
import { CATEGORY_ICONS, CATEGORY_LABELS } from './care-labels';

@Component({
  selector: 'app-category-filter',
  template: `
    <fieldset class="category-filter" [disabled]="disabled()">
      <legend class="visually-hidden">Categoria</legend>
      <div class="filter-track">
        <label class="filter-chip">
          <input class="visually-hidden" type="radio" name="category-filter" value="" [checked]="!selected()"
                 (change)="selectedChange.emit('')">
          <span class="material-symbol" aria-hidden="true">apps</span> Todas
        </label>
        @for (category of categories; track category) {
          <label class="filter-chip" [attr.data-category]="category">
            <input class="visually-hidden" type="radio" name="category-filter" [value]="category"
                   [checked]="category === selected()" (change)="selectedChange.emit(category)">
            <span class="material-symbol" aria-hidden="true">{{ icons[category] }}</span> {{ labels[category] }}
          </label>
        }
      </div>
    </fieldset>
  `,
})
export class CategoryFilter {
  readonly disabled = input(false);
  readonly selected = input('');
  readonly selectedChange = output<string>();
  protected readonly categories = Object.keys(CATEGORY_LABELS) as (keyof typeof CATEGORY_LABELS)[];
  protected readonly labels = CATEGORY_LABELS;
  protected readonly icons = CATEGORY_ICONS;
}
